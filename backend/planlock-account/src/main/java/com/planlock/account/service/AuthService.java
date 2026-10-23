package com.planlock.account.service;

import com.planlock.account.dto.AuthResponse;
import com.planlock.account.dto.BackupPasswordResetRequest;
import com.planlock.account.dto.BackupPasswordSetRequest;
import com.planlock.account.dto.DeviceRegisterRequest;
import com.planlock.account.dto.LoginRequest;
import com.planlock.account.dto.RegisterRequest;
import com.planlock.account.dto.SendCodeRequest;
import com.planlock.account.entity.Device;
import com.planlock.account.entity.User;
import com.planlock.account.mapper.DeviceMapper;
import com.planlock.account.mapper.MergeMapper;
import com.planlock.account.mapper.UserMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.common.auth.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 认证与账号服务（account 模块）。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserMapper userMapper;
    private final DeviceMapper deviceMapper;
    private final MergeMapper mergeMapper;
    private final VerificationCodeService verificationCodeService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final DeploymentAuthLimiter deploymentAuthLimiter;

    @Value("${planlock.deployment.enabled:false}")
    private boolean deploymentEnabled;

    @Transactional
    public AuthResponse deviceRegister(DeviceRegisterRequest req) {
        if (deploymentEnabled) {
            return touchAuthenticatedDeploymentDevice(req);
        }
        Device existing = deviceMapper.findByDeviceUid(req.getDeviceUid());
        if (existing != null) {
            User owner = userMapper.findById(existing.getUserId());
            if (owner != null && owner.getPhone() == null && owner.getEmail() == null) {
                deviceMapper.touch(existing.getId(), LocalDateTime.now());
                return buildAuth(existing, null);
            }
            // A device identifier is not an account credential (e.g. after reinstall).
            User anonymous = new User();
            userMapper.insert(anonymous);
            return buildAuth(bindDevice(req.getDeviceUid(), req.getDeviceName(), req.getPlatform(), anonymous.getId()), null);
        }
        User anonymous = new User();
        userMapper.insert(anonymous);
        Device device = bindDevice(req.getDeviceUid(), req.getDeviceName(), req.getPlatform(), anonymous.getId());
        return buildAuth(device, null);
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (deploymentEnabled) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "公开注册已关闭，请使用实例所有者账号登录");
        }
        if (!verificationCodeService.verify(req.getAccount(), req.getCode())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "验证码错误或已过期");
        }
        User user = createAccount(req.getAccount(), req.getPassword());
        Long guestUserId = resolveGuestUserId(req.getDeviceUid(), user.getId());
        Device device = bindDevice(req.getDeviceUid(), req.getDeviceName(), req.getPlatform(), user.getId());
        return buildAuth(device, guestUserId);
    }

    /** Only the transaction-locked deployment setup service calls this controlled entry point. */
    @Transactional
    public Long createSetupOwner(String account, String password) {
        if (!hasText(password)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "所有者必须设置密码");
        }
        return createAccount(account, password).getId();
    }

    private User createAccount(String account, String password) {
        if (userMapper.findByAccount(account) != null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "账号已存在");
        }
        User user = new User();
        if (isEmail(account)) {
            user.setEmail(account);
        } else {
            user.setPhone(account);
        }
        if (hasText(password)) {
            user.setPasswordHash(passwordEncoder.encode(password));
        }
        userMapper.insert(user);
        return user;
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        rejectDeploymentCode(req.getCode());
        String source = deploymentEnabled ? deploymentAuthLimiter.sourceKey() : null;
        if (deploymentEnabled) deploymentAuthLimiter.check(source);
        User user = userMapper.findByAccount(req.getAccount());
        String account = deploymentEnabled ? deploymentAuthLimiter.accountKey(user == null ? null : user.getId(), req.getAccount()) : null;
        if (deploymentEnabled) {
            deploymentAuthLimiter.reserve(source, account);
            if (user == null || !hasText(req.getPassword()) || user.getPasswordHash() == null
                    || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "账号或密码错误");
            }
        } else {
            if (user == null) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "账号或密码错误");
            }
            if (hasText(req.getCode())) {
                if (!verificationCodeService.verify(req.getAccount(), req.getCode())) {
                    throw new BusinessException(ErrorCode.INVALID_PARAM, "验证码错误或已过期");
                }
            } else if (hasText(req.getPassword())) {
                if (user.getPasswordHash() == null || !passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
                    throw new BusinessException(ErrorCode.INVALID_PARAM, "账号或密码错误");
                }
            } else {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "请提供密码或验证码");
            }
        }
        Long guestUserId = resolveGuestUserId(req.getDeviceUid(), user.getId());
        Device device = bindDevice(req.getDeviceUid(), req.getDeviceName(), req.getPlatform(), user.getId());
        if (deploymentEnabled) deploymentAuthLimiter.succeeded(source, account);
        return buildAuth(device, guestUserId);
    }

    @Transactional
    public Map<String, Long> merge(Long currentUserId, Long guestUserId, String guestToken) {
        Map<String, Long> result = new HashMap<>();
        if (guestUserId == null || guestUserId.equals(currentUserId)) {
            result.put("mergedPlans", 0L);
            result.put("mergedSchedules", 0L);
            return result;
        }
        User source = userMapper.findById(guestUserId);
        boolean permitted = false;
        try {
            var proof = jwtUtil.parse(guestToken);
            permitted = source != null && source.getPhone() == null && source.getEmail() == null
                    && guestUserId.equals(proof.get("uid", Long.class))
                    && java.util.Objects.equals(com.planlock.common.auth.UserContext.deviceId(), proof.get("did", Long.class))
                    && proof.get("did", Long.class) != null;
        } catch (Exception ignored) { /* Missing, expired or forged source proof is never a merge grant. */ }
        if (!permitted) throw new BusinessException(ErrorCode.UNAUTHORIZED, "游客合并来源未验证，原内容已保留");
        int plans = mergeMapper.transferPlans(guestUserId, currentUserId);
        result.put("mergedPlanTypes", (long) mergeMapper.transferPlanTypes(guestUserId, currentUserId));
        mergeMapper.clearGuestPlanTypes(guestUserId);
        result.put("mergedPlans", (long) plans);
        result.put("mergedSchedules", (long) mergeMapper.transferSchedules(guestUserId, currentUserId));
        mergeMapper.reassignDeletedPlanTypes(currentUserId);
        return result;
    }

    @Transactional
    public void setBackupPassword(Long userId, BackupPasswordSetRequest req) {
        userMapper.updateBackupPassword(userId,
                passwordEncoder.encode(req.getBackupPassword()),
                req.getSecurityQuestion(),
                hasText(req.getSecurityAnswer()) ? passwordEncoder.encode(req.getSecurityAnswer()) : null);
    }

    @Transactional
    public void resetBackupPassword(BackupPasswordResetRequest req) {
        rejectDeploymentCode(req.getCode());
        String source = deploymentEnabled ? deploymentAuthLimiter.sourceKey() : null;
        if (deploymentEnabled) deploymentAuthLimiter.check(source);
        User user = userMapper.findByAccount(req.getAccount());
        String account = deploymentEnabled ? deploymentAuthLimiter.accountKey(user == null ? null : user.getId(), req.getAccount()) : null;
        if (deploymentEnabled) {
            deploymentAuthLimiter.reserve(source, account);
            if (user == null || !hasText(req.getSecurityAnswer()) || user.getSecurityAnswerHash() == null
                    || !passwordEncoder.matches(req.getSecurityAnswer(), user.getSecurityAnswerHash())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "账号或密码错误");
            }
        } else {
            if (user == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "账号不存在");
            }
            if (hasText(req.getCode())) {
                if (!verificationCodeService.verify(req.getAccount(), req.getCode())) {
                    throw new BusinessException(ErrorCode.INVALID_PARAM, "验证码错误或已过期");
                }
            } else if (hasText(req.getSecurityAnswer())) {
                if (user.getSecurityAnswerHash() == null
                        || !passwordEncoder.matches(req.getSecurityAnswer(), user.getSecurityAnswerHash())) {
                    throw new BusinessException(ErrorCode.INVALID_PARAM, "密保答案错误");
                }
            } else {
                throw new BusinessException(ErrorCode.INVALID_PARAM, "请提供验证码或密保答案");
            }
        }
        userMapper.updateBackupPassword(user.getId(),
                passwordEncoder.encode(req.getNewBackupPassword()),
                user.getSecurityQuestion(),
                user.getSecurityAnswerHash());
        if (deploymentEnabled) deploymentAuthLimiter.succeeded(source, account);
    }

    public void sendCode(SendCodeRequest req) {
        rejectDeploymentCode("send");
        verificationCodeService.issue(req.getAccount());
    }

    private void rejectDeploymentCode(String code) {
        if (deploymentEnabled && hasText(code)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "自建实例不支持验证码，请使用账号密码");
        }
    }

    private AuthResponse touchAuthenticatedDeploymentDevice(DeviceRegisterRequest req) {
        var attributes = RequestContextHolder.getRequestAttributes();
        String authorization = attributes instanceof ServletRequestAttributes servlet
                ? servlet.getRequest().getHeader("Authorization") : null;
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请使用账号密码登录自建实例");
        }
        Long userId;
        Long deviceId;
        try {
            var claims = jwtUtil.parse(authorization.substring(7));
            userId = claims.get("uid", Long.class);
            deviceId = claims.get("did", Long.class);
            if (userId == null || deviceId == null) throw new IllegalArgumentException("Missing identity");
        } catch (Exception invalidToken) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请使用账号密码登录自建实例");
        }
        Device existing = deviceMapper.findByDeviceUid(req.getDeviceUid());
        if (existing == null || !userId.equals(existing.getUserId()) || !deviceId.equals(existing.getId())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "请使用账号密码登录自建实例");
        }
        deviceMapper.touch(existing.getId(), LocalDateTime.now());
        return buildAuth(existing, null);
    }

    private AuthResponse buildAuth(Device device, Long guestUserId) {
        return AuthResponse.builder()
                .token(jwtUtil.generate(device.getUserId(), device.getId()))
                .userId(device.getUserId())
                .deviceId(device.getId())
                .guestUserId(guestUserId)
                .build();
    }

    private Long resolveGuestUserId(String deviceUid, Long accountUserId) {
        Device existing = deviceMapper.findByDeviceUid(deviceUid);
        if (existing == null || existing.getUserId().equals(accountUserId)) {
            return null;
        }
        User previous = userMapper.findById(existing.getUserId());
        if (previous != null && previous.getPhone() == null && previous.getEmail() == null) {
            return previous.getId();
        }
        return null;
    }

    private Device bindDevice(String deviceUid, String deviceName, String platform, Long userId) {
        Device device = deviceMapper.findByDeviceUid(deviceUid);
        LocalDateTime now = LocalDateTime.now();
        if (device == null) {
            device = new Device();
            device.setDeviceUid(deviceUid);
            device.setDeviceName(deviceName);
            device.setPlatform(platform);
            device.setUserId(userId);
            device.setIsPrimary(true);
            device.setLastOnlineAt(now);
            deviceMapper.insert(device);
        } else {
            device.setUserId(userId);
            if (hasText(deviceName)) {
                device.setDeviceName(deviceName);
            }
            if (hasText(platform)) {
                device.setPlatform(platform);
            }
            device.setLastOnlineAt(now);
            deviceMapper.update(device);
        }
        return device;
    }

    private boolean isEmail(String account) {
        return account != null && account.contains("@");
    }

    private boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
