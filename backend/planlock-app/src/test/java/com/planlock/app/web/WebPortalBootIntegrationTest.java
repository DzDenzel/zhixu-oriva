package com.planlock.app.web;

import com.planlock.PlanLockApplication;
import com.planlock.account.service.AuthService;
import com.planlock.app.setup.DeploymentMetadataService;
import com.planlock.common.DeploymentMetadataProvider;
import com.planlock.common.ErrorCode;
import com.planlock.common.auth.JwtUtil;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 Spring Boot 自动配置 + MyBatis {@code @MapperScan} 全栈集成测试（关闭 B-DEF-01/02/03 & A1 & R1~R3）：
 * <ol>
 *   <li>验证真实 {@code PlanLockApplication} 下 {@code @MapperScan(annotationClass = Mapper.class)} 绝不劫持
 *       {@code DeploymentMetadataProvider} SPI 接口，且所有 {@code @Mapper} 均完成真实 MyBatis 绑定；</li>
 *   <li>验证 R1 统一匿名防枚举边界：全部真实公开端点匿名可达；其余任意未认证 {@code /api/**}（含 v1、非 v1 及
 *       {@code /api/v1/plugins/*} 未知子路径）全 {@code Accept} 矩阵均恒定返回 {@code 200 JSON 1001 + no-store}；</li>
 *   <li>验证 R2 无通配抢占下的原生 MVC 语义：已知路由错误 HTTP 方法返回 {@code 405 + Allow + JSON + no-store}，
 *       {@code OPTIONS}/{@code HEAD} 语义完好，已认证未知 {@code /api/**} 经 {@code NoResourceFoundException}
 *       返回 {@code 404 JSON 1003 + no-store}，静态 {@code /setup-assets/*} 保持 200；</li>
 *   <li>验证 R3 {@code /setup} RFC 9110 {@code Accept} 优先级协商（含 {@code text/html;q=0, *&#47;*;q=0.8}、
 *       重复 Accept 头、非法 q 值与初始化后恒定 410）。</li>
 * </ol>
 */
@SpringBootTest(
        classes = {PlanLockApplication.class, WebPortalBootIntegrationTest.MockDataSourceConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "planlock.deployment.enabled=true",
        "planlock.deployment.setup-token=boot-test-secret",
        "planlock.version=2.2.0",
        "planlock.jwt.secret=planlock-real-boot-integration-secret-32b",
        "planlock.jwt.expire-minutes=60"
})
class WebPortalBootIntegrationTest {

    @TempDir
    static Path tempPluginsDir;

    @DynamicPropertySource
    static void registerPluginDir(DynamicPropertyRegistry registry) throws Exception {
        Path officialDir = Files.createDirectories(tempPluginsDir.resolve("official"));
        Files.writeString(officialDir.resolve("sample-1.0.0.plp"), "signed-artifact-bytes", StandardCharsets.UTF_8);
        registry.add("planlock.plugins.directory", () -> tempPluginsDir.toAbsolutePath().toString());
    }

    @TestConfiguration
    static class MockDataSourceConfig {
        @Bean
        @Primary
        DataSource dataSource() {
            return mock(DataSource.class);
        }
    }

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private MockMvc mvc;

    private Map<String, Object> deploymentRow;

    @BeforeEach
    void setUp() {
        reset(jdbcTemplate);
        deploymentRow = new HashMap<>();
        deploymentRow.put("instance_id", "2c6f9664-1c46-4b9f-9710-cac1de5e389d");
        deploymentRow.put("initialized", true);
        deploymentRow.put("instance_label", "书房服务器");
        deploymentRow.put("owner_user_id", 1L);
        when(jdbcTemplate.queryForMap(anyString())).thenAnswer(inv -> new HashMap<>(deploymentRow));
    }

    @Test
    void myBatisMapperScanDoesNotHijackDeploymentMetadataProviderAndBindsRealMappers() {
        Map<String, DeploymentMetadataProvider> providers = context.getBeansOfType(DeploymentMetadataProvider.class);
        assertThat(providers).hasSize(1);
        DeploymentMetadataProvider onlyProvider = providers.values().iterator().next();
        assertThat(onlyProvider).isInstanceOf(DeploymentMetadataService.class);
        assertThat(Proxy.isProxyClass(onlyProvider.getClass())).isFalse();

        Object injected = ReflectionTestUtils.getField(authService, "deploymentMetadataProvider");
        assertThat(injected).isSameAs(onlyProvider);

        var myBatisConfig = sqlSessionFactory.getConfiguration();
        assertThat(myBatisConfig.hasStatement("com.planlock.account.mapper.UserMapper.findById")).isTrue();
        assertThat(myBatisConfig.hasStatement("com.planlock.account.mapper.DeviceMapper.findById")).isTrue();
        assertThat(myBatisConfig.hasStatement("com.planlock.common.DeploymentMetadataProvider.getMetadata")).isFalse();

        var snapshot = onlyProvider.getMetadata();
        assertThat(snapshot.instanceLabel()).isEqualTo("书房服务器");
        assertThat(snapshot.initialized()).isTrue();
        assertThat(snapshot.ownerUserId()).isEqualTo(1L);
    }

    @Test
    void anonymousApiAntiEnumerationAndPublicEndpointsHoldAcrossAllAcceptHeaders() throws Exception {
        List<String> accepts = List.of("", "application/json", "text/html", "text/html,application/json;q=0.8", "*/*");

        // Public static assets remain 200.
        mvc.perform(get("/setup-assets/setup.css")).andExpect(status().isOk());
        mvc.perform(get("/setup-assets/setup.js")).andExpect(status().isOk());

        // Public real API endpoints remain anonymously reachable (never blocked with 1001).
        mvc.perform(get("/api/v1/health"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.version").value("2.2.0"));
        mvc.perform(get("/api/v1/plugins/catalog/official"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value("official"));
        mvc.perform(get("/api/v1/plugins/catalog/third-party"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value("third-party"));
        mvc.perform(head("/api/v1/health"))
                .andExpect(header().string("Cache-Control", "no-store"));

        // R1: ALL unauthenticated /api/** (v1, non-v1, unknown plugin subpaths) return uniform 200 JSON 1001 + no-store.
        for (String path : List.of(
                "/api/v1/me",
                "/api/v1/not-exists",
                "/api/v1/me/unknown",
                "/api/unknown",
                "/api/v2/secret",
                "/api/v1/plugins/unknown",
                "/api/v1/plugins/catalog/unknown",
                "/api/v1/plugins/artifacts/official/",
                "/api/v1/plugins/artifacts/third-party/")) {
            for (String accept : accepts) {
                var req = get(path);
                if (!accept.isEmpty()) {
                    req = req.header("Accept", accept);
                }
                var res = mvc.perform(req)
                        .andExpect(status().isOk())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()))
                        .andReturn()
                        .getResponse();
                assertThat(res.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("<html", "<!doctype");
            }
        }

        // R1a: Unauthenticated JSON body requests to non-v1 or plugin unknown routes return JSON 1001 + no-store + nosniff.
        for (String path : List.of("/api/v1/not-exists", "/api/unknown", "/api/v1/plugins/unknown")) {
            mvc.perform(post(path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Accept", "text/html")
                            .content("{\"probe\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        }

        // R1b: Unauthenticated non-JSON cross-method requests on protected endpoints AND MVC path-decoding variants
        // (/%61pi/..., /api;v=1/..., /api/v1;x=1/..., //api/...) are intercepted BEFORE 405 dispatch -> 200 JSON 1001 + no-store.
        for (var req : List.of(
                post("/api/v1/me"),
                delete("/api/v1/me"),
                put("/api/v1/me"),
                patch("/api/v1/me"),
                options("/api/v1/me"),
                delete("/api/v1/not-exists"),
                post("/api/unknown"),
                delete("/api/v1/plugins/catalog/other"),
                delete(URI.create("/%61pi/v1/me")),
                post(URI.create("/%61pi/v1/me")),
                delete(URI.create("/api;v=1/v1/me")),
                delete(URI.create("/api/v1;x=1/me")),
                delete(URI.create("http://localhost//api/v1/me")))) {
            mvc.perform(req.header("Accept", "text/html"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
        }

        // R1b: Public artifact download existing -> 200 octet-stream, missing -> empty 404 (documented public artifact policy).
        mvc.perform(get("/api/v1/plugins/artifacts/official/sample-1.0.0.plp"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().string("signed-artifact-bytes"));
        var missingArtifact = mvc.perform(get("/api/v1/plugins/artifacts/official/missing-artifact.plp"))
                .andExpect(status().isNotFound())
                .andReturn()
                .getResponse();
        assertThat(missingArtifact.getContentAsString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void methodNotAllowedPreservesAllowHeaderOptionsHeadWorkAndAuthenticatedUnknownApiReturnsJson404() throws Exception {
        List<String> accepts = List.of("", "application/json", "text/html", "text/html,application/json;q=0.8", "*/*");
        String token = jwtUtil.generate(1L, 10L);

        // R2: Known public POST-only route called with GET returns 405 + non-empty Allow: POST + JSON (even for Accept: text/html).
        for (String accept : List.of("", "application/json", "text/html")) {
            var req = get("/api/v1/auth/login");
            if (!accept.isEmpty()) {
                req = req.header("Accept", accept);
            }
            var res = mvc.perform(req)
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn()
                    .getResponse();
            assertThat(res.getHeader("Allow")).contains("POST");
            assertThat(res.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("<html", "<!doctype");
        }

        // R2: Known protected GET-only route called with DELETE returns 405 + Allow containing GET.
        var meDeleteRes = mvc.perform(delete("/api/v1/me")
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "text/html"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn()
                .getResponse();
        assertThat(meDeleteRes.getHeader("Allow")).contains("GET");

        // R2: OPTIONS semantics preserved on real endpoints.
        var loginOptions = mvc.perform(options("/api/v1/auth/login"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        assertThat(loginOptions.getHeader("Allow")).contains("POST");

        var meOptions = mvc.perform(options("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        assertThat(meOptions.getHeader("Allow")).contains("GET");

        // R2 & R2b: Authenticated unknown /api/** AND unsupported plugin catalog source return deterministic 404 JSON 1003 + no-store (never 500).
        for (String path : List.of(
                "/api/v1/not-exists",
                "/api/v1/me/unknown",
                "/api/v1/plugins/unknown",
                "/api/v1/plugins/catalog/other",
                "/api/v1/plugins/catalog/unknown",
                "/api/v1/plugins/catalog/official/extra",
                "/api/v1/plugins/artifacts/official/",
                "/api/v1/plugins/artifacts/third-party/",
                "/api/unknown")) {
            for (String accept : accepts) {
                var req = get(path).header("Authorization", "Bearer " + token);
                if (!accept.isEmpty()) {
                    req = req.header("Accept", accept);
                }
                var res = mvc.perform(req)
                        .andExpect(status().isNotFound())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()))
                        .andReturn()
                        .getResponse();
                assertThat(res.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("<html", "<!doctype");
            }
        }

        // R1b + R2: Authenticated unknown API path variants (encoded / matrix first segment) ALSO classify as API -> 404 JSON 1003 + no-store.
        for (URI variantUnknown : List.of(
                URI.create("/%61pi/v1/not-exists"),
                URI.create("/api;v=1/v1/not-exists"))) {
            mvc.perform(get(variantUnknown)
                            .header("Authorization", "Bearer " + token)
                            .header("Accept", "text/html"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()));
        }

        // R2a: Non-API missing pages & missing static assets return plain HTTP 404 + no-store + nosniff (NO ApiResponse 1003 JSON envelope).
        for (String nonApiMissing : List.of("/no-such-page", "/setup-assets/missing.css")) {
            for (String accept : List.of("", "text/html", "text/css", "application/json", "*/*")) {
                var req = get(nonApiMissing);
                if (!accept.isEmpty()) {
                    req = req.header("Accept", accept);
                }
                var res = mvc.perform(req)
                        .andExpect(status().isNotFound())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andReturn()
                        .getResponse();
                assertThat(res.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("\"code\":1003", "\"code\": 1003");
            }
        }
    }

    @Test
    void setupRfc9110AcceptPriorityNegotiationAndInitialized410Hold() throws Exception {
        // Initialized: every Accept variant (including complex q=0, post-q params & duplicate q) receives deterministic 410 JSON + no-store.
        deploymentRow.put("initialized", true);
        for (String accept : List.of(
                "",
                "text/html",
                "application/json",
                "text/html;q=0, */*;q=0.8",
                "text/html;q=0.9;charset=iso-8859-1",
                "text/html;q=0;q=0.9",
                "text/html;q=0.9;charset=utf-8",
                "*/*")) {
            var req = get("/setup");
            if (!accept.isEmpty()) {
                req = req.header("Accept", accept);
            }
            mvc.perform(req)
                    .andExpect(status().isGone())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }

        // Pending: HTML / text/* / */* / mixed q>0 / parameterized specificity overrides (in any order) & RFC 9110 valid q=1. accept HTML.
        // Argon RFC 9110 ruling: standalone charset="utf-8" -> 200; foo="a,b;q=0" combined with */*;q=0.5 -> 200; standalone foo="a,b;q=0" -> 406.
        deploymentRow.put("initialized", false);
        for (String accept : List.of(
                "",
                "text/html",
                "text/html;charset=utf-8",
                "text/html;charset=\"utf-8\"",
                "text/html;charset=\"utf-8\";q=0.9",
                "text/html;q=1.",
                "text/html;q=0.9;charset=utf-8, */*;q=0.1",
                "text/html;foo=\"a,b;q=0\";q=0.9, */*;q=0.5",
                "text/html;q=0, text/html;charset=utf-8;q=0.8",
                "text/html;charset=iso-8859-1;q=0, text/html;q=0.8",
                "text/*;q=0, text/*;charset=utf-8;q=0.8",
                "*/*;q=0, */*;charset=utf-8;q=0.8",
                "text/*",
                "*/*",
                "application/json;q=0.9, text/html;q=0.5")) {
            var req = get("/setup");
            if (!accept.isEmpty()) {
                req = req.header("Accept", accept);
            }
            mvc.perform(req)
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                    .andExpect(header().string("Cache-Control", "no-store"));
        }

        // R3: Undeclared params (including standalone quoted foo="a,b;q=0" per Argon RFC 9110 ruling), post-q incompatible params,
        // duplicate q in single range, parameterized q=0, non-matching wildcard params, RFC 9110 q=0., invalid q & JSON-only -> 406.
        for (String rejectedAccept : List.of(
                "application/json",
                "text/html;q=0, */*;q=0.8",
                "text/*;q=0, */*;q=0.9",
                "text/html;charset=utf-8;q=0, text/html;q=0.9",
                "text/html;q=0;charset=utf-8, text/html;q=0.9",
                "text/html;foo=\"a,b;q=0\";q=0.9",
                "text/html;q=0.9;charset=iso-8859-1",
                "text/html;q=0.9;foo=bar",
                "text/html;q=0;q=0.9",
                "text/html;q=0.9;q=0",
                "text/html;q=0;q=0.9, */*;q=0.8",
                "text/html;q=\"0.8\"",
                "text/*;charset=utf-8;q=0, text/*;q=0.8",
                "*/*;charset=utf-8;q=0, */*;q=0.8",
                "text/html;charset=iso-8859-1",
                "text/*;charset=iso-8859-1",
                "*/*;foo=bar",
                "text/html;q=0",
                "text/html;q=0.",
                "text/html;q=0., */*;q=0.8",
                "text/html;q=1.5",
                "text/html;q=0.1234",
                "text/html;q=abc",
                "invalid-media-type")) {
            mvc.perform(get("/setup").header("Accept", rejectedAccept))
                    .andExpect(status().isNotAcceptable())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }

        // R3: Duplicate Accept headers (both exact conflict -> 406, and more specific charset=utf-8 winning -> 200).
        mvc.perform(get("/setup")
                        .header("Accept", "text/html;q=0")
                        .header("Accept", "*/*"))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(get("/setup")
                        .header("Accept", "text/html;charset=utf-8;q=0")
                        .header("Accept", "text/html;q=0.9"))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        mvc.perform(get("/setup")
                        .header("Accept", "text/html;q=0")
                        .header("Accept", "text/html;charset=utf-8;q=0.6"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"));

        // Portal explicit route with JSON-only Accept gets 406 JSON (never 500).
        deploymentRow.put("initialized", true);
        mvc.perform(get("/").header("Accept", "application/json"))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
