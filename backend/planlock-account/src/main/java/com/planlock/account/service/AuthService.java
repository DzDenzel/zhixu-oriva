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

    @Transactional
    public AuthResponse deviceRegister(DeviceRegisterRequest req) {
        Device existing = deviceMapper.findByDeviceUid(req.getDeviceUid());
        if (existing != null) {
            deviceMapper.touch(existing.getId(), LocalDateTime.now());
            return buildAuth(existing, null);
        }
        User anonymous = new User();
        userMapper.insert(anonymous);
        Device device = bindDevice(req.getDeviceUid(), req.getDeviceName(), req.getPlatform(), anonymous.getId());
        return buildAuth(device, null);
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        if (!verificationCodeService.verify(req.getAccount(), req.getCode())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "验证码错误或已过期");
        }
        if (userMapper.findByAccount(req.getAccount()) != null) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "账号已存在");
        }
        User user = new User();
        if (isEmail(req.getAccount())) {
            user.setEmail(req.getAccount());
        } else {
            user.setPhone(req.getAccount());
        }
        if (hasText(req.getPassword())) {
            user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        }
        userMapper.insert(user);
        Long guestUserId = resolveGuestUserId(req.getDeviceUid(), user.getId());
        Device device = bindDevice(req.getDeviceUid(), req.getDeviceName(), req.getPlatform(), user.getId());
        return buildAuth(device, guestUserId);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user = userMapper.findByAccount(req.getAccount());
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
        Long guestUserId = resolveGuestUserId(req.getDeviceUid(), user.getId());
        Device device = bindDevice(req.getDeviceUid(), req.getDeviceName(), req.getPlatform(), user.getId());
        return buildAuth(device, guestUserId);
    }

    @Transactional
    public Map<String, Long> merge(Long currentUserId, Long guestUserId) {
        Map<String, Long> result = new HashMap<>();
        if (guestUserId == null || guestUserId.equals(currentUserId)) {
            result.put("mergedPlans", 0L);
            return result;
        }
        int plans = mergeMapper.transferPlans(guestUserId, currentUserId);
        result.put("mergedPlans", (long) plans);
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
        User user = userMapper.findByAccount(req.getAccount());
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
        userMapper.updateBackupPassword(user.getId(),
                passwordEncoder.encode(req.getNewBackupPassword()),
                user.getSecurityQuestion(),
                user.getSecurityAnswerHash());
    }

    public void sendCode(SendCodeRequest req) {
        verificationCodeService.issue(req.getAccount());
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
