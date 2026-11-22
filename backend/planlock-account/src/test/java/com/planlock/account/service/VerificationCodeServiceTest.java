package com.planlock.account.service;

import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VerificationCodeService 单元测试（纯 JVM + Mockito，覆盖限频、6位码签发、过期与错误校验）。
 */
@ExtendWith(MockitoExtension.class)
class VerificationCodeServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private VerificationCodeService service;

    @BeforeEach
    void setUp() {
        service = new VerificationCodeService(redis);
    }

    @Test
    void issueGeneratesSixDigitCodeAndSetsCodeAndRateLimitTtl() {
        when(redis.hasKey("verify:limit:13800138000")).thenReturn(false);
        when(redis.opsForValue()).thenReturn(valueOps);

        String code = service.issue("13800138000");

        assertThat(code).matches("[0-9]{6}");
        verify(valueOps).set("verify:13800138000", code, Duration.ofMinutes(5));
        verify(valueOps).set("verify:limit:13800138000", "1", Duration.ofSeconds(60));
    }

    @Test
    void issueRejectsWithinRateLimitWindow() {
        when(redis.hasKey("verify:limit:user@example.com")).thenReturn(true);

        assertThatThrownBy(() -> service.issue("user@example.com"))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.INVALID_PARAM.getCode()));
        verify(redis, never()).opsForValue();
    }

    @Test
    void verifyReturnsTrueOnlyOnExactMatch() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("verify:13800138000")).thenReturn("042819");

        assertThat(service.verify("13800138000", "042819")).isTrue();
        assertThat(service.verify("13800138000", "042818")).isFalse();
        assertThat(service.verify("13800138000", "42819")).isFalse();
    }

    @Test
    void verifyReturnsFalseWhenExpiredOrNullArguments() {
        assertThat(service.verify(null, "123456")).isFalse();
        assertThat(service.verify("13800138000", null)).isFalse();
        verify(redis, never()).opsForValue();

        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("verify:expired@example.com")).thenReturn(null);
        assertThat(service.verify("expired@example.com", "123456")).isFalse();
    }
}
