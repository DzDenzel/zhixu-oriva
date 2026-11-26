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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实 Spring Boot 自动配置 + MyBatis {@code @MapperScan} 全栈集成测试（关闭 B-DEF-01 / B-DEF-02 / B-DEF-03 / A1）：
 * <ol>
 *   <li>验证真实 {@code PlanLockApplication} 下 {@code @MapperScan(annotationClass = Mapper.class)} 绝不劫持
 *       {@code DeploymentMetadataProvider} SPI 接口，且所有 {@code @Mapper} 均完成真实 MyBatis 绑定；</li>
 *   <li>验证真实 Boot 自动配置（含 {@code ResourceHttpRequestHandler}、{@code JsonBodyLimitFilter}、
 *       {@code AuthInterceptor}、{@code ApiFallbackController}、{@code GlobalExceptionHandler}）下
 *       全 {@code Accept} 矩阵返回确定性 JSON 与正确静态资源服务；</li>
 *   <li>验证未初始化/已初始化 {@code GET /setup} 全 {@code Accept} 矩阵不再产生 500 或缺失 {@code no-store}。</li>
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
    void realSpringBootUnknownApiMatrixAndStaticResourcesHoldAcrossAllAcceptHeaders() throws Exception {
        List<String> accepts = List.of("", "application/json", "text/html", "text/html,application/json;q=0.8", "*/*");

        // Static assets must remain reachable under real Boot ResourceHttpRequestHandler.
        mvc.perform(get("/setup-assets/setup.css"))
                .andExpect(status().isOk());
        mvc.perform(get("/setup-assets/setup.js"))
                .andExpect(status().isOk());

        // Unauthenticated requests to known or unknown /api/v1/** return deterministic 200 JSON 1001 across all Accept headers.
        for (String path : List.of("/api/v1/me", "/api/v1/not-exists", "/api/v1/me/unknown")) {
            for (String accept : accepts) {
                var req = get(path);
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

        // Unauthenticated JSON body request with Accept: text/html hits JsonBodyLimitFilter and still returns JSON 1001.
        mvc.perform(post("/api/v1/not-exists")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Accept", "text/html")
                        .content("{\"probe\":true}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));

        // Authenticated unknown /api/** returns deterministic 404 JSON 1003 across all Accept headers (never HTML 404).
        String token = jwtUtil.generate(1L, 10L);
        for (String path : List.of("/api/v1/not-exists", "/api/v1/me/unknown", "/api/v1/plugins/not-exists", "/api/unknown")) {
            for (String accept : accepts) {
                var req = get(path).header("Authorization", "Bearer " + token);
                if (!accept.isEmpty()) {
                    req = req.header("Accept", accept);
                }
                var res = mvc.perform(req)
                        .andExpect(status().isNotFound())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.code").value(ErrorCode.NOT_FOUND.getCode()))
                        .andReturn()
                        .getResponse();
                assertThat(res.getContentAsString(StandardCharsets.UTF_8)).doesNotContain("<html", "<!doctype");
            }
        }
    }

    @Test
    void setupContentNegotiationReturnsDeterministic410WhenInitializedAnd406ForJsonWhenPending() throws Exception {
        // Initialized: every Accept header receives deterministic 410 + Cache-Control: no-store + JSON (never 500).
        deploymentRow.put("initialized", true);
        for (String accept : List.of("", "text/html", "application/json", "text/html,application/json;q=0.8", "*/*")) {
            var req = get("/setup");
            if (!accept.isEmpty()) {
                req = req.header("Accept", accept);
            }
            mvc.perform(req)
                    .andExpect(status().isGone())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        }

        // Uninitialized: HTML / wildcard gets 200 text/html; JSON-only Accept gets 406 (never 500).
        deploymentRow.put("initialized", false);
        mvc.perform(get("/setup").header("Accept", "text/html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"));

        mvc.perform(get("/setup").header("Accept", "application/json"))
                .andExpect(status().isNotAcceptable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        // Portal explicit route with JSON-only Accept gets 406 JSON (never 500).
        deploymentRow.put("initialized", true);
        mvc.perform(get("/").header("Accept", "application/json"))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
