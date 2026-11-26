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
import com.planlock.common.auth.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService 单元测试（纯 JVM + Mockito，覆盖游客注册、登录、限流、合并鉴权与备用密码路径）。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private DeviceMapper deviceMapper;

    @Mock
    private MergeMapper mergeMapper;

    @Mock
    private VerificationCodeService verificationCodeService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private DeploymentAuthLimiter deploymentAuthLimiter;

    private JwtUtil jwtUtil;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("planlock-test-secret-key-0123456789", 60);
        authService = new AuthService(
                userMapper,
                deviceMapper,
                mergeMapper,
                verificationCodeService,
                passwordEncoder,
                jwtUtil,
                deploymentAuthLimiter
        );
        ReflectionTestUtils.setField(authService, "deploymentEnabled", false);
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void deviceRegisterCreatesAnonymousUserAndDeviceWhenUidIsNew() {
        DeviceRegisterRequest req = deviceReq("uid-new", "Pixel", "android");
        when(deviceMapper.findByDeviceUid("uid-new")).thenReturn(null);
        doAnswer(inv -> {
            ((User) inv.getArgument(0)).setId(101L);
            return 1;
        }).when(userMapper).insert(any(User.class));
        doAnswer(inv -> {
            ((Device) inv.getArgument(0)).setId(201L);
            return 1;
        }).when(deviceMapper).insert(any(Device.class));

        AuthResponse res = authService.deviceRegister(req);

        assertThat(res.getUserId()).isEqualTo(101L);
        assertThat(res.getDeviceId()).isEqualTo(201L);
        assertThat(res.getGuestUserId()).isNull();
        assertThat(jwtUtil.getUserId(res.getToken())).isEqualTo(101L);
        assertThat(jwtUtil.getDeviceId(res.getToken())).isEqualTo(201L);
    }

    @Test
    void deviceRegisterTouchesExistingAnonymousOwnerWithoutCreatingNewUser() {
        Device existing = device(202L, 102L, "uid-anon");
        User anonymous = new User();
        anonymous.setId(102L);
        when(deviceMapper.findByDeviceUid("uid-anon")).thenReturn(existing);
        when(userMapper.findById(102L)).thenReturn(anonymous);

        AuthResponse res = authService.deviceRegister(deviceReq("uid-anon", "Pixel", "android"));

        verify(deviceMapper).touch(eq(202L), any(LocalDateTime.class));
        verify(userMapper, never()).insert(any());
        assertThat(res.getUserId()).isEqualTo(102L);
        assertThat(res.getDeviceId()).isEqualTo(202L);
    }

    @Test
    void deviceRegisterDoesNotIssueRegisteredAccountTokenWhenDeviceUidReused() {
        Device existing = device(203L, 50L, "uid-reinstall");
        User registeredOwner = new User();
        registeredOwner.setId(50L);
        registeredOwner.setPhone("13800138000");

        when(deviceMapper.findByDeviceUid("uid-reinstall")).thenReturn(existing);
        when(userMapper.findById(50L)).thenReturn(registeredOwner);
        doAnswer(inv -> {
            ((User) inv.getArgument(0)).setId(103L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        AuthResponse res = authService.deviceRegister(deviceReq("uid-reinstall", "Phone", "android"));

        assertThat(res.getUserId()).isEqualTo(103L);
        assertThat(res.getDeviceId()).isEqualTo(203L);
        verify(deviceMapper).update(existing);
        assertThat(existing.getUserId()).isEqualTo(103L);
    }

    @Test
    void deviceRegisterInDeploymentModeRequiresMatchingAuthenticatedBearerToken() {
        ReflectionTestUtils.setField(authService, "deploymentEnabled", true);
        DeviceRegisterRequest req = deviceReq("uid-dep", "Phone", "android");

        assertBusinessError(() -> authService.deviceRegister(req), ErrorCode.UNAUTHORIZED);

        String validToken = jwtUtil.generate(7L, 88L);
        MockHttpServletRequest httpReq = new MockHttpServletRequest();
        httpReq.addHeader("Authorization", "Bearer " + validToken);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpReq));

        Device bound = device(88L, 7L, "uid-dep");
        when(deviceMapper.findByDeviceUid("uid-dep")).thenReturn(bound);

        AuthResponse res = authService.deviceRegister(req);

        verify(deviceMapper).touch(eq(88L), any(LocalDateTime.class));
        assertThat(res.getUserId()).isEqualTo(7L);
        assertThat(res.getDeviceId()).isEqualTo(88L);
    }

    @Test
    void registerSucceedsAndCapturesPreviousGuestUserId() {
        RegisterRequest req = new RegisterRequest();
        req.setAccount("user@example.com");
        req.setCode("123456");
        req.setPassword("secret123");
        req.setDeviceUid("uid-guest");
        req.setDeviceName("MyPhone");
        req.setPlatform("android");

        when(verificationCodeService.verify("user@example.com", "123456")).thenReturn(true);
        when(userMapper.findByAccount("user@example.com")).thenReturn(null);
        when(passwordEncoder.encode("secret123")).thenReturn("hashed-pw");
        doAnswer(inv -> {
            ((User) inv.getArgument(0)).setId(300L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        Device prevDevice = device(400L, 99L, "uid-guest");
        User prevGuest = new User();
        prevGuest.setId(99L);
        when(deviceMapper.findByDeviceUid("uid-guest")).thenReturn(prevDevice);
        when(userMapper.findById(99L)).thenReturn(prevGuest);

        AuthResponse res = authService.register(req);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("user@example.com");
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("hashed-pw");
        assertThat(res.getUserId()).isEqualTo(300L);
        assertThat(res.getDeviceId()).isEqualTo(400L);
        assertThat(res.getGuestUserId()).isEqualTo(99L);
    }

    @Test
    void registerRejectsDeploymentModeBadCodeAndDuplicateAccount() {
        RegisterRequest req = new RegisterRequest();
        req.setAccount("13800138000");
        req.setCode("000000");
        req.setDeviceUid("uid-1");
        req.setPlatform("android");

        ReflectionTestUtils.setField(authService, "deploymentEnabled", true);
        assertBusinessError(() -> authService.register(req), ErrorCode.INVALID_PARAM);

        ReflectionTestUtils.setField(authService, "deploymentEnabled", false);
        when(verificationCodeService.verify("13800138000", "000000")).thenReturn(false);
        assertBusinessError(() -> authService.register(req), ErrorCode.INVALID_PARAM);

        when(verificationCodeService.verify("13800138000", "000000")).thenReturn(true);
        User duplicate = new User();
        duplicate.setId(1L);
        when(userMapper.findByAccount("13800138000")).thenReturn(duplicate);
        assertBusinessError(() -> authService.register(req), ErrorCode.INVALID_PARAM);
    }

    @Test
    void createSetupOwnerRequiresPasswordAndCreatesAccount() {
        assertBusinessError(() -> authService.createSetupOwner("owner@example.com", "   "), ErrorCode.INVALID_PARAM);

        when(userMapper.findByAccount("owner@example.com")).thenReturn(null);
        when(passwordEncoder.encode("StrongPass!1")).thenReturn("bcrypt-hash");
        doAnswer(inv -> {
            ((User) inv.getArgument(0)).setId(1L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        Long ownerId = authService.createSetupOwner("owner@example.com", "StrongPass!1");

        assertThat(ownerId).isEqualTo(1L);
    }

    @Test
    void loginNormalModeCoversPasswordCodeAndErrorBranches() {
        User user = new User();
        user.setId(10L);
        user.setPhone("13900139000");
        user.setPasswordHash("encoded-pw");
        when(userMapper.findByAccount("13900139000")).thenReturn(user);

        LoginRequest noCred = loginReq("13900139000", null, null);
        assertBusinessError(() -> authService.login(noCred), ErrorCode.INVALID_PARAM);

        LoginRequest wrongPw = loginReq("13900139000", "bad", null);
        when(passwordEncoder.matches("bad", "encoded-pw")).thenReturn(false);
        assertBusinessError(() -> authService.login(wrongPw), ErrorCode.INVALID_PARAM);

        LoginRequest badCode = loginReq("13900139000", null, "111111");
        when(verificationCodeService.verify("13900139000", "111111")).thenReturn(false);
        assertBusinessError(() -> authService.login(badCode), ErrorCode.INVALID_PARAM);

        LoginRequest okCode = loginReq("13900139000", null, "654321");
        when(verificationCodeService.verify("13900139000", "654321")).thenReturn(true);
        when(deviceMapper.findByDeviceUid("uid-login")).thenReturn(null);
        doAnswer(inv -> {
            ((Device) inv.getArgument(0)).setId(55L);
            return 1;
        }).when(deviceMapper).insert(any(Device.class));

        AuthResponse res = authService.login(okCode);
        assertThat(res.getUserId()).isEqualTo(10L);
        assertThat(res.getDeviceId()).isEqualTo(55L);
    }

    @Test
    void loginDeploymentModeEnforcesLimiterAndClearsBudgetOnSuccess() {
        ReflectionTestUtils.setField(authService, "deploymentEnabled", true);

        LoginRequest withCode = loginReq("owner@example.com", null, "123456");
        assertBusinessError(() -> authService.login(withCode), ErrorCode.INVALID_PARAM);

        User owner = new User();
        owner.setId(1L);
        owner.setEmail("owner@example.com");
        owner.setPasswordHash("encoded");
        when(deploymentAuthLimiter.sourceKey()).thenReturn("src-key");
        when(userMapper.findByAccount("owner@example.com")).thenReturn(owner);
        when(deploymentAuthLimiter.accountKey(1L, "owner@example.com")).thenReturn("acc-key");

        LoginRequest badPw = loginReq("owner@example.com", "wrong", null);
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);
        assertBusinessError(() -> authService.login(badPw), ErrorCode.INVALID_PARAM);
        verify(deploymentAuthLimiter).reserve("src-key", "acc-key");
        verify(deploymentAuthLimiter, never()).succeeded(any(), any());

        LoginRequest okPw = loginReq("owner@example.com", "correct", null);
        when(passwordEncoder.matches("correct", "encoded")).thenReturn(true);
        when(deviceMapper.findByDeviceUid("uid-login")).thenReturn(device(66L, 1L, "uid-login"));

        AuthResponse res = authService.login(okPw);

        assertThat(res.getUserId()).isEqualTo(1L);
        assertThat(res.getDeviceId()).isEqualTo(66L);
        verify(deploymentAuthLimiter).succeeded("src-key", "acc-key");
    }

    @Test
    void loginDeploymentModePropagatesRateLimitBlockBeforeCheckingPassword() {
        ReflectionTestUtils.setField(authService, "deploymentEnabled", true);
        when(deploymentAuthLimiter.sourceKey()).thenReturn("src-blocked");
        doThrow(new BusinessException(ErrorCode.INVALID_PARAM, "登录尝试过多，请 15 分钟后重试"))
                .when(deploymentAuthLimiter).check("src-blocked");

        assertBusinessError(() -> authService.login(loginReq("owner@example.com", "pw", null)), ErrorCode.INVALID_PARAM);
        verify(userMapper, never()).findByAccount(any());
    }

    @Test
    void mergeValidatesGuestProofAndTransfersOwnedRecords() {
        Map<String, Long> noop = authService.merge(10L, null, null);
        assertThat(noop).containsEntry("mergedPlans", 0L).containsEntry("mergedSchedules", 0L).containsEntry("mergedJournals", 0L);

        User guest = new User();
        guest.setId(5L);
        when(userMapper.findById(5L)).thenReturn(guest);
        UserContext.set(10L, 77L);

        // Wrong deviceId in proof must be rejected
        String otherDeviceToken = jwtUtil.generate(5L, 88L);
        assertBusinessError(() -> authService.merge(10L, 5L, otherDeviceToken), ErrorCode.UNAUTHORIZED);
        verify(mergeMapper, never()).transferPlans(any(), any());
        verify(mergeMapper, never()).transferJournals(any(), any());

        // Matching guest userId and deviceId succeeds
        String validGuestToken = jwtUtil.generate(5L, 77L);
        when(mergeMapper.transferPlans(5L, 10L)).thenReturn(3);
        when(mergeMapper.transferPlanTypes(5L, 10L)).thenReturn(2);
        when(mergeMapper.transferSchedules(5L, 10L)).thenReturn(4);
        when(mergeMapper.rekeyConflictingGuestJournals(5L, 10L)).thenReturn(1);
        when(mergeMapper.transferJournals(5L, 10L)).thenReturn(5);

        Map<String, Long> merged = authService.merge(10L, 5L, validGuestToken);

        assertThat(merged)
                .containsEntry("mergedPlans", 3L)
                .containsEntry("mergedPlanTypes", 2L)
                .containsEntry("mergedSchedules", 4L)
                .containsEntry("mergedJournals", 5L)
                .containsEntry("rekeyedJournals", 1L);
        verify(mergeMapper).clearGuestPlanTypes(5L);
        verify(mergeMapper).rekeyConflictingGuestJournals(5L, 10L);
        verify(mergeMapper).clearDuplicateGuestJournals(5L, 10L);
        verify(mergeMapper).reassignDeletedPlanTypes(10L);
    }

    @Test
    void backupPasswordSetResetAndSendCodeRespectModeRules() {
        BackupPasswordSetRequest setReq = new BackupPasswordSetRequest();
        setReq.setBackupPassword("backup-123");
        setReq.setSecurityQuestion("pet?");
        setReq.setSecurityAnswer("mimi");
        when(passwordEncoder.encode("backup-123")).thenReturn("hash-bp");
        when(passwordEncoder.encode("mimi")).thenReturn("hash-ans");

        authService.setBackupPassword(10L, setReq);
        verify(userMapper).updateBackupPassword(10L, "hash-bp", "pet?", "hash-ans");

        User user = new User();
        user.setId(10L);
        user.setEmail("u@example.com");
        user.setSecurityQuestion("pet?");
        user.setSecurityAnswerHash("hash-ans");
        when(userMapper.findByAccount("u@example.com")).thenReturn(user);
        when(passwordEncoder.matches("mimi", "hash-ans")).thenReturn(true);
        when(passwordEncoder.encode("new-bp")).thenReturn("hash-new-bp");

        BackupPasswordResetRequest resetReq = new BackupPasswordResetRequest();
        resetReq.setAccount("u@example.com");
        resetReq.setSecurityAnswer("mimi");
        resetReq.setNewBackupPassword("new-bp");
        authService.resetBackupPassword(resetReq);
        verify(userMapper).updateBackupPassword(10L, "hash-new-bp", "pet?", "hash-ans");

        SendCodeRequest sendReq = new SendCodeRequest();
        sendReq.setAccount("u@example.com");
        sendReq.setScene("login");
        authService.sendCode(sendReq);
        verify(verificationCodeService).issue("u@example.com");

        ReflectionTestUtils.setField(authService, "deploymentEnabled", true);
        assertBusinessError(() -> authService.sendCode(sendReq), ErrorCode.INVALID_PARAM);
    }

    private static DeviceRegisterRequest deviceReq(String uid, String name, String platform) {
        DeviceRegisterRequest req = new DeviceRegisterRequest();
        req.setDeviceUid(uid);
        req.setDeviceName(name);
        req.setPlatform(platform);
        return req;
    }

    private static LoginRequest loginReq(String account, String password, String code) {
        LoginRequest req = new LoginRequest();
        req.setAccount(account);
        req.setPassword(password);
        req.setCode(code);
        req.setDeviceUid("uid-login");
        req.setDeviceName("Phone");
        req.setPlatform("android");
        return req;
    }

    private static Device device(Long id, Long userId, String uid) {
        Device d = new Device();
        d.setId(id);
        d.setUserId(userId);
        d.setDeviceUid(uid);
        return d;
    }

    private static void assertBusinessError(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(expected.getCode()));
    }
}
