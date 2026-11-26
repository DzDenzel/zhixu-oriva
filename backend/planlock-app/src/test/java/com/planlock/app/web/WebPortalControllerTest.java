package com.planlock.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.account.controller.MeController;
import com.planlock.account.entity.Device;
import com.planlock.account.entity.User;
import com.planlock.account.mapper.DeviceMapper;
import com.planlock.account.mapper.MergeMapper;
import com.planlock.account.mapper.UserMapper;
import com.planlock.account.service.AuthService;
import com.planlock.account.service.DeploymentAuthLimiter;
import com.planlock.account.service.VerificationCodeService;
import com.planlock.app.setup.DeploymentMetadataService;
import com.planlock.app.setup.SetupController;
import com.planlock.app.setup.SetupService;
import com.planlock.common.ErrorCode;
import com.planlock.common.GlobalExceptionHandler;
import com.planlock.common.auth.AuthInterceptor;
import com.planlock.common.auth.JwtUtil;
import com.planlock.common.web.WebConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 数字站同源基座集成测试（REQ-041 包 A）：
 * 1. 同时装配 SetupService + AuthService + DeploymentMetadataService + MeController + WebPortalController + WebConfig，
 *    验证 Spring Bean 依赖单向无环；
 * 2. 验证未初始化 302 -> /setup、已初始化显式路由返回带严格安全头 HTML、缺失资源安全占位、/setup 维持 410 GONE；
 * 3. 验证未知 /api/** 绝不回退 HTML，GET /api/v1/me 强制 JWT 鉴权并聚合实例元数据。
 */
@SpringJUnitWebConfig(WebPortalControllerTest.Config.class)
@TestPropertySource(properties = {
        "planlock.deployment.enabled=true",
        "planlock.deployment.setup-token=portal-test-secret",
        "planlock.version=2.2.0",
        "planlock.jwt.secret=planlock-portal-integration-test-secret-32b",
        "planlock.jwt.expire-minutes=60"
})
class WebPortalControllerTest {

    @Configuration
    @EnableWebMvc
    @Import({
            WebConfig.class,
            AuthInterceptor.class,
            JwtUtil.class,
            GlobalExceptionHandler.class,
            AuthService.class,
            MeController.class,
            DeploymentMetadataService.class,
            SetupService.class,
            SetupController.class,
            WebPortalController.class,
            ApiFallbackController.class
    })
    static class Config {
        @Bean JdbcTemplate jdbcTemplate() { return mock(JdbcTemplate.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean StringRedisTemplate redis() { return mock(StringRedisTemplate.class); }
        @Bean UserMapper userMapper() { return mock(UserMapper.class); }
        @Bean DeviceMapper deviceMapper() { return mock(DeviceMapper.class); }
        @Bean MergeMapper mergeMapper() { return mock(MergeMapper.class); }
        @Bean VerificationCodeService verificationCodeService() { return mock(VerificationCodeService.class); }
        @Bean DeploymentAuthLimiter deploymentAuthLimiter() { return mock(DeploymentAuthLimiter.class); }
        @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper userMapper;
    @Autowired DeviceMapper deviceMapper;
    @Autowired JwtUtil jwtUtil;
    @Autowired SetupService setupService;
    @Autowired DeploymentMetadataService metadataService;

    private MockMvc mvc;
    private Map<String, Object> deploymentRow;

    @BeforeEach
    void setUp() {
        reset(jdbc, userMapper, deviceMapper);
        deploymentRow = new HashMap<>();
        deploymentRow.put("instance_id", "2c6f9664-1c46-4b9f-9710-cac1de5e389d");
        deploymentRow.put("initialized", false);
        deploymentRow.put("instance_label", null);
        deploymentRow.put("owner_user_id", null);
        when(jdbc.queryForMap(anyString())).thenAnswer(inv -> new HashMap<>(deploymentRow));
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void uninitializedDeploymentRedirectsExplicitSpaRoutesToSetupWithNoStore() throws Exception {
        for (String route : List.of("/", "/today", "/plans", "/schedules", "/journal", "/rhythm", "/devices", "/plugins", "/login")) {
            mvc.perform(get(route))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/setup"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("X-Frame-Options", "DENY"));
        }
    }

    @Test
    void initializedDeploymentServesSafeFallbackHtmlWithStrictSecurityHeadersAndKeepsSetupGone() throws Exception {
        deploymentRow.put("initialized", true);
        deploymentRow.put("instance_label", "我的个人知序");

        for (String route : List.of("/", "/plans", "/journal", "/rhythm", "/plugins", "/login")) {
            var result = mvc.perform(get(route))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Content-Security-Policy", WebPortalController.STRICT_CSP))
                    .andReturn();
            String html = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(html)
                    .contains("知序 ORIVA")
                    .doesNotContain("portal-test-secret", "cdn.", "https://", "http://", "<script");
        }

        // /setup remains 410 GONE + no-store across all Accept headers once initialized.
        for (String accept : List.of("", "text/html", "application/json", "*/*")) {
            var req = get("/setup");
            if (!accept.isEmpty()) {
                req = req.header("Accept", accept);
            }
            mvc.perform(req)
                    .andExpect(status().isGone())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }
    }

    @Test
    void initializedDeploymentServesPackagedWebIndexHtmlWhenPresentOnClasspath() throws Exception {
        deploymentRow.put("initialized", true);
        MockMvc customMvc = MockMvcBuilders
                .standaloneSetup(new WebPortalController(setupService, "web-test/index.html"))
                .build();

        String html = customMvc.perform(get("/journal"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Security-Policy", WebPortalController.STRICT_CSP))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("oriva-web-index-fixture");
    }

    @Test
    void unknownApiPathsNeverFallBackToHtmlWhetherUnauthenticatedOrAuthenticated() throws Exception {
        deploymentRow.put("initialized", true);
        List<String> accepts = List.of("", "application/json", "text/html", "text/html,application/json;q=0.8", "*/*");

        // Unauthenticated unknown /api/v1/** preserves pre-auth contract (200 JSON 1001, never 500 HTML).
        for (String unknownApi : List.of("/api/v1/not-exists", "/api/v1/me/unknown")) {
            for (String accept : accepts) {
                var req = get(unknownApi);
                if (!accept.isEmpty()) {
                    req = req.header("Accept", accept);
                }
                var res = mvc.perform(req)
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()))
                        .andReturn()
                        .getResponse();
                assertThat(res.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("<html", "<!doctype");
            }
        }

        // Authenticated unknown /api/v1/** returns deterministic 404 JSON 1003 across all Accept headers.
        String token = jwtUtil.generate(1L, 10L);
        for (String unknownApi : List.of("/api/v1/not-exists", "/api/v1/me/unknown")) {
            for (String accept : accepts) {
                var req = get(unknownApi).header("Authorization", "Bearer " + token);
                if (!accept.isEmpty()) {
                    req = req.header("Accept", accept);
                }
                var res = mvc.perform(req)
                        .andExpect(status().isNotFound())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()))
                        .andReturn()
                        .getResponse();
                assertThat(res.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("<html", "<!doctype");
            }
        }
    }

    @Test
    void meEndpointIntegratesWithDeploymentMetadataProviderAndProtectsUnauthenticatedAccess() throws Exception {
        deploymentRow.put("initialized", true);
        deploymentRow.put("instance_label", "书房服务器");
        deploymentRow.put("owner_user_id", 5L);

        // Must require JWT (not in PUBLIC_API_PATTERNS).
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        User user = new User();
        user.setId(5L);
        user.setEmail("owner@oriva.local");
        user.setPasswordHash("bcrypt-secret");
        Device dev = new Device();
        dev.setId(55L);
        dev.setUserId(5L);
        dev.setDeviceName("Firefox");
        dev.setPlatform("web");
        when(userMapper.findById(5L)).thenReturn(user);
        when(deviceMapper.findById(55L)).thenReturn(dev);

        String token = jwtUtil.generate(5L, 55L);
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(5))
                .andExpect(jsonPath("$.data.deviceId").value(55))
                .andExpect(jsonPath("$.data.accountMasked").value("ow***r@oriva.local"))
                .andExpect(jsonPath("$.data.userType").value("OWNER"))
                .andExpect(jsonPath("$.data.guest").value(false))
                .andExpect(jsonPath("$.data.instanceLabel").value("书房服务器"))
                .andExpect(jsonPath("$.data.deploymentEnabled").value(true))
                .andExpect(jsonPath("$.data.version").value("2.2.0"));
    }

    @Test
    void metadataProviderAndPortalDegradeSafelyWithoutLeakingDbDiagnostics() throws Exception {
        when(jdbc.queryForMap(anyString())).thenThrow(new IllegalStateException("jdbc:mysql://secret-db-pass"));

        var meta = metadataService.getMetadata();
        assertThat(meta.instanceLabel()).isNull();
        assertThat(meta.initialized()).isFalse();
        assertThat(meta.deploymentEnabled()).isTrue();
        assertThat(meta.version()).isEqualTo("2.2.0");

        var res = mvc.perform(get("/"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .getResponse();
        assertThat(res.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("secret-db-pass", "jdbc:");
    }

    @Test
    void nonDeploymentModeServesPortalDirectlyWithoutRedirectingToDisabledSetup() throws Exception {
        SetupService devSetup = new SetupService(jdbc, mock(AuthService.class), new ObjectMapper(), false, "", "");
        MockMvc devMvc = MockMvcBuilders
                .standaloneSetup(new WebPortalController(devSetup))
                .build();

        devMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Content-Security-Policy", WebPortalController.STRICT_CSP));
    }
}
