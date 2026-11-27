package com.planlock.account.controller;

import com.planlock.account.entity.Device;
import com.planlock.account.entity.User;
import com.planlock.account.mapper.DeviceMapper;
import com.planlock.account.mapper.MergeMapper;
import com.planlock.account.mapper.UserMapper;
import com.planlock.account.service.AuthService;
import com.planlock.account.service.DeploymentAuthLimiter;
import com.planlock.account.service.VerificationCodeService;
import com.planlock.common.DeploymentMetadataProvider;
import com.planlock.common.ErrorCode;
import com.planlock.common.GlobalExceptionHandler;
import com.planlock.common.auth.AuthInterceptor;
import com.planlock.common.auth.JwtUtil;
import com.planlock.common.auth.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/v1/me MockMvc 集成单测：覆盖鉴权保护、脱敏格式、游客身份、多用户隔离与 no-store 缓存头。
 */
@ExtendWith(MockitoExtension.class)
class MeControllerTest {

    private static final String TEST_SECRET = "planlock-me-controller-test-secret-32b";

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
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil(TEST_SECRET, 60L);
        authService = new AuthService(
                userMapper,
                deviceMapper,
                mergeMapper,
                verificationCodeService,
                passwordEncoder,
                jwtUtil,
                deploymentAuthLimiter
        );
        DeploymentMetadataProvider provider = () -> new DeploymentMetadataProvider.DeploymentMetadata(
                "测试个人后端", true, true, 1L, "2.2.0");
        ReflectionTestUtils.setField(authService, "deploymentMetadataProvider", provider);

        mvc = MockMvcBuilders.standaloneSetup(new MeController(authService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new AuthInterceptor(jwtUtil))
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void unauthenticatedAndInvalidJwtAreRejectedWith1001() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer invalid.jwt.token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void authenticatedOwnerReturnsMaskedProfileWithNoStoreAndZeroSecretLeakage() throws Exception {
        User owner = new User();
        owner.setId(1L);
        owner.setEmail("alice.owner@example.com");
        owner.setPasswordHash("$2a$10$verysecretpasswordhash");
        owner.setBackupPasswordHash("$2a$10$verysecretbackuphash");
        owner.setSecurityAnswerHash("$2a$10$verysecretanswerhash");
        Device device = new Device();
        device.setId(11L);
        device.setUserId(1L);
        device.setDeviceName("Chrome Web");
        device.setPlatform("web");

        when(userMapper.findById(1L)).thenReturn(owner);
        when(deviceMapper.findById(11L)).thenReturn(device);

        String token = jwtUtil.generate(1L, 11L);
        String body = mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(1))
                .andExpect(jsonPath("$.data.deviceId").value(11))
                .andExpect(jsonPath("$.data.accountMasked").value("al***r@example.com"))
                .andExpect(jsonPath("$.data.userType").value("OWNER"))
                .andExpect(jsonPath("$.data.guest").value(false))
                .andExpect(jsonPath("$.data.deviceName").value("Chrome Web"))
                .andExpect(jsonPath("$.data.devicePlatform").value("web"))
                .andExpect(jsonPath("$.data.instanceLabel").value("测试个人后端"))
                .andExpect(jsonPath("$.data.deploymentEnabled").value(true))
                .andExpect(jsonPath("$.data.version").value("2.2.0"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(
                "alice.owner@example.com",
                "verysecretpasswordhash",
                "verysecretbackuphash",
                "verysecretanswerhash"
        );
    }

    @Test
    void guestAndMultiUserIsolationReturnStrictPerUserScope() throws Exception {
        User guest = new User();
        guest.setId(2L);
        when(userMapper.findById(2L)).thenReturn(guest);

        User phoneUser = new User();
        phoneUser.setId(3L);
        phoneUser.setPhone("13912345678");
        when(userMapper.findById(3L)).thenReturn(phoneUser);

        Device user3Device = new Device();
        user3Device.setId(33L);
        user3Device.setUserId(3L);
        user3Device.setDeviceName("User3 Phone");
        user3Device.setPlatform("android");
        when(deviceMapper.findById(33L)).thenReturn(user3Device);

        // Guest JWT claiming user3's deviceId must not see user3's device info.
        String guestToken = jwtUtil.generate(2L, 33L);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + guestToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(2))
                .andExpect(jsonPath("$.data.guest").value(true))
                .andExpect(jsonPath("$.data.userType").value("GUEST"))
                .andExpect(jsonPath("$.data.accountMasked").doesNotExist())
                .andExpect(jsonPath("$.data.deviceId").doesNotExist())
                .andExpect(jsonPath("$.data.deviceName").doesNotExist());

        // Phone user (non-owner userId=3 != ownerUserId=1) gets STANDARD and only own masked phone/device.
        String phoneToken = jwtUtil.generate(3L, 33L);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + phoneToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(3))
                .andExpect(jsonPath("$.data.userType").value("STANDARD"))
                .andExpect(jsonPath("$.data.accountMasked").value("139****5678"))
                .andExpect(jsonPath("$.data.deviceId").value(33))
                .andExpect(jsonPath("$.data.deviceName").value("User3 Phone"));

        // Unbound/deleted device must return null deviceId (never echo stale JWT did).
        when(deviceMapper.findById(99L)).thenReturn(null);
        String unboundToken = jwtUtil.generate(3L, 99L);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + unboundToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deviceId").doesNotExist())
                .andExpect(jsonPath("$.data.deviceName").doesNotExist())
                .andExpect(jsonPath("$.data.devicePlatform").doesNotExist());

        // Unauthenticated request with Accept: text/html must still return JSON 1001, never 500 HTML.
        mvc.perform(get("/api/v1/me").header("Accept", "text/html"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(org.springframework.http.MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
    }
}
