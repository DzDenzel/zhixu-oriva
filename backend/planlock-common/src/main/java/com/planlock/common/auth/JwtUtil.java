package com.planlock.common.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与解析（游客与登录用户均持有 token）。
 */
@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expireMinutes;

    public JwtUtil(@Value("${planlock.jwt.secret}") String secret,
                   @Value("${planlock.jwt.expire-minutes}") long expireMinutes) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException(
                    "planlock.jwt.secret 长度须 >= 32 字节（HMAC-SHA256 要求），当前 " + bytes.length + " 字节");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.expireMinutes = expireMinutes;
    }

    public String generate(Long userId, Long deviceId) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("uid", userId)
                .claim("did", deviceId)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMinutes * 60_000L))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(String token) {
        return parse(token).get("uid", Long.class);
    }

    public Long getDeviceId(String token) {
        return parse(token).get("did", Long.class);
    }
}
