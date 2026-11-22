package com.planlock.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JwtUtil 单元测试（不依赖外部环境）。
 */
class JwtUtilTest {

    private final JwtUtil jwtUtil = new JwtUtil("planlock-test-secret-key-0123456789", 60);

    @Test
    void generateThenParseReturnsClaims() {
        String token = jwtUtil.generate(1L, 2L);

        Claims claims = jwtUtil.parse(token);

        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("uid", Long.class)).isEqualTo(1L);
        assertThat(claims.get("did", Long.class)).isEqualTo(2L);
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        assertThat(jwtUtil.getUserId(token)).isEqualTo(1L);
        assertThat(jwtUtil.getDeviceId(token)).isEqualTo(2L);
    }

    @Test
    void differentSecretsProduceDifferentTokens() {
        JwtUtil other = new JwtUtil("another-secret-key-0123456789abcdef", 60);
        String token = jwtUtil.generate(1L, 2L);

        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> other.parse(token));
    }

    @Test
    void constructorRejectsSecretShorterThan32BytesAndAcceptsExact32Bytes() {
        assertThatThrownBy(() -> new JwtUtil("short-secret-31-bytes-012345678", 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(">= 32");

        JwtUtil exact = new JwtUtil("0123456789abcdef0123456789abcdef", 60);
        String token = exact.generate(99L, null);
        assertThat(exact.getUserId(token)).isEqualTo(99L);
        assertThat(exact.getDeviceId(token)).isNull();
    }

    @Test
    void expiredTokenThrowsExpiredJwtException() {
        JwtUtil expiredUtil = new JwtUtil("planlock-test-secret-key-0123456789", -1);
        String token = expiredUtil.generate(10L, 20L);

        assertThatThrownBy(() -> jwtUtil.parse(token))
                .isInstanceOf(ExpiredJwtException.class);
        assertThatThrownBy(() -> jwtUtil.getUserId(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tamperedPayloadThrowsSignatureException() {
        String token = jwtUtil.generate(1L, 2L);
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"999\",\"uid\":999,\"did\":2}".getBytes(StandardCharsets.UTF_8));
        String tampered = parts[0] + "." + forgedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwtUtil.parse(tampered))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void malformedBlankOrNullTokenThrowsException() {
        assertThatThrownBy(() -> jwtUtil.parse("not.a.valid.jwt"))
                .isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> jwtUtil.parse("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jwtUtil.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
