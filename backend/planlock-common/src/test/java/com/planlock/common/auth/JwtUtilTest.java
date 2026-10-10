package com.planlock.common.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtUtil 单元测试（不依赖外部环境）。
 */
class JwtUtilTest {

    private final JwtUtil jwtUtil = new JwtUtil("planlock-test-secret-key-0123456789", 60);

    @Test
    void generateThenParseReturnsClaims() {
        String token = jwtUtil.generate(1L, 2L);

        Claims claims = jwtUtil.parse(token);

        assertThat(claims.get("uid", Long.class)).isEqualTo(1L);
        assertThat(claims.get("did", Long.class)).isEqualTo(2L);
        assertThat(jwtUtil.getUserId(token)).isEqualTo(1L);
        assertThat(jwtUtil.getDeviceId(token)).isEqualTo(2L);
    }

    @Test
    void differentSecretsProduceDifferentTokens() {
        JwtUtil other = new JwtUtil("another-secret-key-0123456789abcdef", 60);
        String token = jwtUtil.generate(1L, 2L);

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> other.parse(token));
    }
}
