package com.planlock.account.service;

import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeploymentAuthLimiter 单元测试（覆盖可信代理链解析、账号键归一化、限流阈值与 fail-closed）。
 */
@ExtendWith(MockitoExtension.class)
class DeploymentAuthLimiterTest {

    @Mock
    private StringRedisTemplate redis;

    private DeploymentAuthLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new DeploymentAuthLimiter(redis);
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void sourceKeyFallsBackToUnknownWithoutServletContext() {
        String key = limiter.sourceKey();

        assertThat(key).startsWith("deployment:auth:source:");
        assertThat(key.substring("deployment:auth:source:".length())).matches("[0-9a-f]{64}");
    }

    @Test
    void sourceKeyIgnoresForwardedHeaderWhenNoTrustedProxiesConfigured() {
        MockHttpServletRequest directReq = new MockHttpServletRequest();
        directReq.setRemoteAddr("203.0.113.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(directReq));
        String directKey = limiter.sourceKey();

        MockHttpServletRequest spoofedReq = new MockHttpServletRequest();
        spoofedReq.setRemoteAddr("203.0.113.10");
        spoofedReq.addHeader("X-Forwarded-For", "198.51.100.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(spoofedReq));
        String spoofedKey = limiter.sourceKey();

        assertThat(spoofedKey).isEqualTo(directKey);
    }

    @Test
    void sourceKeyExtractsClosestUntrustedHopBehindTrustedProxies() {
        ReflectionTestUtils.setField(limiter, "trustedProxies", "127.0.0.1, 10.0.0.1");

        MockHttpServletRequest directClient = new MockHttpServletRequest();
        directClient.setRemoteAddr("198.51.100.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(directClient));
        String expectedClientKey = limiter.sourceKey();

        MockHttpServletRequest proxiedWithSpoofedLeft = new MockHttpServletRequest();
        proxiedWithSpoofedLeft.setRemoteAddr("127.0.0.1");
        proxiedWithSpoofedLeft.addHeader("X-Forwarded-For", "1.1.1.1, 198.51.100.7, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(proxiedWithSpoofedLeft));

        assertThat(limiter.sourceKey()).isEqualTo(expectedClientKey);
    }

    @Test
    void sourceKeyFallsBackToDirectAddressOnMalformedForwardedChain() {
        ReflectionTestUtils.setField(limiter, "trustedProxies", "127.0.0.1");

        MockHttpServletRequest directProxy = new MockHttpServletRequest();
        directProxy.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(directProxy));
        String proxyKey = limiter.sourceKey();

        MockHttpServletRequest badHeader = new MockHttpServletRequest();
        badHeader.setRemoteAddr("127.0.0.1");
        badHeader.addHeader("X-Forwarded-For", "evil.example.com, 198.51.100.7");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(badHeader));

        assertThat(limiter.sourceKey()).isEqualTo(proxyKey);
    }

    @Test
    void accountKeyNormalizesUnknownAccountAndUsesIdForKnownUser() {
        assertThat(limiter.accountKey(77L, "User@Example.com"))
                .isEqualTo("deployment:auth:account:id:77");

        String lower = limiter.accountKey(null, "user@example.com");
        String paddedUpper = limiter.accountKey(null, "  USER@EXAMPLE.COM  ");
        assertThat(lower).isEqualTo(paddedUpper);
        assertThat(lower).startsWith("deployment:auth:account:unknown:");
        assertThat(limiter.accountKey(null, null)).startsWith("deployment:auth:account:unknown:");
    }

    @Test
    void checkAllowsBelowLimitBlocksAtFiveAndFailsClosedOnNull() {
        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of("k1")))).thenReturn(4L);
        assertThatCode(() -> limiter.check("k1")).doesNotThrowAnyException();

        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of("k1")))).thenReturn(5L);
        assertThatThrownBy(() -> limiter.check("k1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM.getCode()));

        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(List.of("k1")))).thenReturn(null);
        assertThatThrownBy(() -> limiter.check("k1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reserveAllowsZeroBlocksNegativeAndFailsClosedOnNull() {
        List<String> keys = List.of("src", "acc");

        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(keys))).thenReturn(0L);
        assertThatCode(() -> limiter.reserve("src", "acc")).doesNotThrowAnyException();

        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(keys))).thenReturn(-1L);
        assertThatThrownBy(() -> limiter.reserve("src", "acc"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM.getCode()));

        when(redis.execute(ArgumentMatchers.<RedisScript<Long>>any(), eq(keys))).thenReturn(null);
        assertThatThrownBy(() -> limiter.reserve("src", "acc"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void succeededClearsBothBudgets() {
        limiter.succeeded("src", "acc");

        verify(redis).delete(List.of("src", "acc"));
    }
}
