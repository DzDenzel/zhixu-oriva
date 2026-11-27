package com.planlock.common.web;

import com.planlock.common.ErrorCode;
import com.planlock.common.auth.JwtUtil;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-041 R1a / R1b 单元测试：
 * <ul>
 *   <li>R1a：{@link JsonBodyLimitFilter} 预认证 1001 与载荷超限 1002 固定 JSON 响应显式携带 {@code Cache-Control: no-store} 与 {@code X-Content-Type-Options: nosniff}；</li>
 *   <li>R1b：{@link ProtectedApiPreAuthFilter} 在进入路由方法匹配（405）前拦截任意方法、无 JSON 头的匿名受保护 {@code /api/**} 请求，同时放行公开路由与携带有效 JWT 的请求。</li>
 * </ul>
 */
class ProtectedApiPreAuthFilterTest {

    private JwtUtil jwtUtil;
    private ProtectedApiPreAuthFilter preAuthFilter;
    private JsonBodyLimitFilter bodyLimitFilter;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil("planlock-r1-preauth-unit-test-secret-32b", 60);
        preAuthFilter = new ProtectedApiPreAuthFilter(jwtUtil);
        JsonBodyLimitProperties props = new JsonBodyLimitProperties();
        props.setMaxJsonRequestBytes(64);
        bodyLimitFilter = new JsonBodyLimitFilter(props, jwtUtil);
    }

    @Test
    void anonymousProtectedApiRequestsAcrossAllMethodsAreBlockedBeforeDispatchWithNoStore() throws Exception {
        for (String method : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")) {
            for (String path : List.of(
                    "/api/v1/me",
                    "/api/v1/not-exists",
                    "/api/unknown",
                    "/api/v1/plugins/catalog/other",
                    "/api/v1/plugins/unknown",
                    "/api/v1/plugins/artifacts/official/",
                    "/api/v1/plugins/artifacts/third-party/",
                    "/api/v1/plugins/artifacts/official",
                    "/api/v1/plugins/artifacts/official/a/b",
                    "/api/v1/health;matrix=1",
                    "/api/v1/plugins/artifacts/official/..",
                    "/%61pi/v1/me",
                    "/%61pi/v1/health",
                    "/api;v=1/v1/me",
                    "/api;v=1/v1/health",
                    "/api/v1;x=1/me",
                    "//api/v1/me",
                    "//api/v1/health")) {
                MockHttpServletRequest req = new MockHttpServletRequest(method, path);
                MockHttpServletResponse res = new MockHttpServletResponse();
                AtomicBoolean proceeded = new AtomicBoolean(false);
                FilterChain chain = (r, s) -> proceeded.set(true);

                preAuthFilter.doFilter(req, res, chain);

                assertThat(proceeded.get()).as("%s %s should be blocked", method, path).isFalse();
                assertThat(res.getStatus()).isEqualTo(200);
                assertThat(res.getContentType()).startsWith("application/json");
                assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
                assertThat(res.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
                assertThat(res.getContentAsString(StandardCharsets.UTF_8))
                        .contains("\"code\":" + ErrorCode.UNAUTHORIZED.getCode());
            }
        }
    }

    @Test
    void publicRoutesNonApiRoutesAndAuthenticatedProtectedRoutesPassThrough() throws Exception {
        for (String path : List.of(
                "/api/v1/health",
                "/api/v1/setup",
                "/api/v1/auth/login",
                "/api/v1/plugins/catalog/official",
                "/api/v1/plugins/catalog/third-party",
                "/api/v1/plugins/artifacts/official/sample.plp",
                "/setup",
                "/no-such-page",
                "/setup-assets/missing.css")) {
            MockHttpServletRequest req = new MockHttpServletRequest("DELETE", path);
            MockHttpServletResponse res = new MockHttpServletResponse();
            AtomicBoolean proceeded = new AtomicBoolean(false);
            preAuthFilter.doFilter(req, res, (r, s) -> proceeded.set(true));
            assertThat(proceeded.get()).as("public or non-api %s should pass through", path).isTrue();
        }

        String token = jwtUtil.generate(7L, 12L);
        MockHttpServletRequest authedDelete = new MockHttpServletRequest("DELETE", "/api/v1/me");
        authedDelete.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse authedRes = new MockHttpServletResponse();
        AtomicBoolean authedProceeded = new AtomicBoolean(false);
        preAuthFilter.doFilter(authedDelete, authedRes, (r, s) -> authedProceeded.set(true));
        assertThat(authedProceeded.get()).isTrue();
    }

    @Test
    void jsonBodyLimitFilterSetsNoStoreAndNosniffOnPreAuthAndPayloadLimitErrors() throws Exception {
        // R1a: pre-auth 1001 JSON response includes Cache-Control: no-store and X-Content-Type-Options: nosniff
        MockHttpServletRequest unauthJson = new MockHttpServletRequest("POST", "/api/v1/plans");
        unauthJson.setContentType("application/json");
        unauthJson.setContent("{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse unauthRes = new MockHttpServletResponse();
        bodyLimitFilter.doFilter(unauthJson, unauthRes, (r, s) -> {
            throw new AssertionError("should not proceed");
        });
        assertThat(unauthRes.getStatus()).isEqualTo(200);
        assertThat(unauthRes.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(unauthRes.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(unauthRes.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":" + ErrorCode.UNAUTHORIZED.getCode());

        // R1a: oversized public JSON error response also carries Cache-Control: no-store and nosniff
        MockHttpServletRequest oversized = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        oversized.setContentType("application/json");
        oversized.setContent(("{\"x\":\"" + "a".repeat(128) + "\"}").getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse overRes = new MockHttpServletResponse();
        bodyLimitFilter.doFilter(oversized, overRes, (r, s) -> {
            throw new AssertionError("should not proceed");
        });
        assertThat(overRes.getStatus()).isEqualTo(200);
        assertThat(overRes.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(overRes.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(overRes.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":" + ErrorCode.INVALID_PARAM.getCode());
    }

    @Test
    void apiPathMatcherAlignsWithSpringMvcSegmentDecodingWithoutSplittingEncodedSlash() {
        assertThat(ApiPathMatcher.isApiPath("/%61pi/v1/me")).isTrue();
        assertThat(ApiPathMatcher.isApiPath("/api;v=1/v1/me")).isTrue();
        assertThat(ApiPathMatcher.isApiPath("/api/v1;x=1/me")).isTrue();
        assertThat(ApiPathMatcher.isApiPath("//api/v1/me")).isTrue();
        // Encoded %2F inside first segment must NOT be expanded into a path separator
        assertThat(ApiPathMatcher.isApiPath("/api%2Fv1/me")).isFalse();
        assertThat(ApiPathMatcher.isApiPath("/no-such-page")).isFalse();

        assertThat(bodyLimitFilter.isProtectedApiRoute("/%61pi/v1/me")).isTrue();
        assertThat(bodyLimitFilter.isProtectedApiRoute("/api;v=1/v1/me")).isTrue();
        assertThat(bodyLimitFilter.isProtectedApiRoute("/api/v1;x=1/me")).isTrue();
        assertThat(bodyLimitFilter.isProtectedApiRoute("//api/v1/me")).isTrue();
        assertThat(bodyLimitFilter.isProtectedApiRoute("/api/v1/plugins/artifacts/official/")).isTrue();
        assertThat(bodyLimitFilter.isProtectedApiRoute("/api/v1/plugins/artifacts/third-party/")).isTrue();
        assertThat(bodyLimitFilter.isProtectedApiRoute("/api/v1/plugins/artifacts/official/demo.pljs")).isFalse();
        assertThat(bodyLimitFilter.isProtectedApiRoute("/api/v1/health")).isFalse();
    }

    @Test
    void servletPathPrefixIsClassifiedIsomorphicallyAcrossPreAuthAndJsonBodyLimitFilters() throws Exception {
        // Simulate a prefix-mapped DispatcherServlet (/portal/*) with contextPath (/ctx):
        // 1) Protected API (/ctx/portal/api/v1/me) -> both filters reject unauthenticated request with 1001 + no-store
        MockHttpServletRequest protectedReq = new MockHttpServletRequest("POST", "/ctx/portal/api/v1/me");
        protectedReq.setContextPath("/ctx");
        protectedReq.setServletPath("/portal");
        protectedReq.setPathInfo("/api/v1/me");
        protectedReq.setContentType("application/json");
        protectedReq.setContent("{\"x\":1}".getBytes(StandardCharsets.UTF_8));

        assertThat(ApiPathMatcher.resolvePathWithinApplication(protectedReq)).isEqualTo("/api/v1/me");
        assertThat(ApiPathMatcher.isApiRequest(protectedReq)).isTrue();
        assertThat(ApiPathMatcher.isProtectedApiRequest(protectedReq)).isTrue();

        MockHttpServletResponse preAuthRes = new MockHttpServletResponse();
        preAuthFilter.doFilter(protectedReq, preAuthRes, (r, s) -> {
            throw new AssertionError("preAuth should block prefixed protected API");
        });
        assertThat(preAuthRes.getStatus()).isEqualTo(200);
        assertThat(preAuthRes.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(preAuthRes.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":" + ErrorCode.UNAUTHORIZED.getCode());

        MockHttpServletRequest protectedJsonReq = new MockHttpServletRequest("POST", "/ctx/portal/api/v1/me");
        protectedJsonReq.setContextPath("/ctx");
        protectedJsonReq.setServletPath("/portal");
        protectedJsonReq.setPathInfo("/api/v1/me");
        protectedJsonReq.setContentType("application/json");
        protectedJsonReq.setContent("{\"x\":1}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse bodyLimitRes = new MockHttpServletResponse();
        bodyLimitFilter.doFilter(protectedJsonReq, bodyLimitRes, (r, s) -> {
            throw new AssertionError("bodyLimitFilter should block prefixed protected API");
        });
        assertThat(bodyLimitRes.getStatus()).isEqualTo(200);
        assertThat(bodyLimitRes.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(bodyLimitRes.getContentAsString(StandardCharsets.UTF_8))
                .contains("\"code\":" + ErrorCode.UNAUTHORIZED.getCode());

        // 2) Public API (/ctx/portal/api/v1/auth/login) -> both filters pass through cleanly
        MockHttpServletRequest publicReq = new MockHttpServletRequest("POST", "/ctx/portal/api/v1/auth/login");
        publicReq.setContextPath("/ctx");
        publicReq.setServletPath("/portal");
        publicReq.setPathInfo("/api/v1/auth/login");
        publicReq.setContentType("application/json");
        publicReq.setContent("{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(ApiPathMatcher.resolvePathWithinApplication(publicReq)).isEqualTo("/api/v1/auth/login");
        assertThat(ApiPathMatcher.isProtectedApiRequest(publicReq)).isFalse();
        AtomicBoolean bodyProceeded = new AtomicBoolean(false);
        bodyLimitFilter.doFilter(publicReq, new MockHttpServletResponse(), (r, s) -> bodyProceeded.set(true));
        assertThat(bodyProceeded.get()).isTrue();
    }
}
