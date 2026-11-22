package com.planlock.common.auth;

import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AuthInterceptor 单元测试（验证鉴权拦截器与 UserContext 生命周期）。
 */
class AuthInterceptorTest {

    private static final String SECRET = "planlock-test-secret-key-0123456789";

    private final JwtUtil jwtUtil = new JwtUtil(SECRET, 60);
    private final AuthInterceptor interceptor = new AuthInterceptor(jwtUtil);

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void preHandlePopulatesUserContextAndAfterCompletionClearsIt() {
        String token = jwtUtil.generate(42L, 7L);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(UserContext.userId()).isEqualTo(42L);
        assertThat(UserContext.deviceId()).isEqualTo(7L);

        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(UserContext.get()).isNull();
        assertThat(UserContext.userId()).isNull();
        assertThat(UserContext.deviceId()).isNull();
    }

    @Test
    void preHandleRejectsMissingOrNonBearerAuthorization() {
        MockHttpServletRequest noHeader = new MockHttpServletRequest();
        assertUnauthorized(noHeader);

        MockHttpServletRequest basicAuth = new MockHttpServletRequest();
        basicAuth.addHeader("Authorization", "Basic dXNlcjpwYXNz");
        assertUnauthorized(basicAuth);
    }

    @Test
    void preHandleRejectsInvalidOrExpiredToken() {
        MockHttpServletRequest malformed = new MockHttpServletRequest();
        malformed.addHeader("Authorization", "Bearer malformed-token");
        assertUnauthorized(malformed);

        JwtUtil expiredUtil = new JwtUtil(SECRET, -5);
        MockHttpServletRequest expired = new MockHttpServletRequest();
        expired.addHeader("Authorization", "Bearer " + expiredUtil.generate(1L, 2L));
        assertUnauthorized(expired);
    }

    @Test
    void preHandleRejectsTokenWithoutUidClaim() {
        String tokenWithoutUid = Jwts.builder()
                .subject("1")
                .claim("did", 2L)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokenWithoutUid);

        assertUnauthorized(request);
        assertThat(UserContext.get()).isNull();
    }

    private void assertUnauthorized(MockHttpServletRequest request) {
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.UNAUTHORIZED.getCode()));
    }
}
