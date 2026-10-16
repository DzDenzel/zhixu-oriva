package com.planlock.account.service;

import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Deployment-only login and security-answer budget. Redis errors deliberately fail closed. */
@Component
@RequiredArgsConstructor
public class DeploymentAuthLimiter {
    private final StringRedisTemplate redis;
    @Value("${planlock.deployment.trusted-proxies:}")
    private String trustedProxies = "";
    private static final DefaultRedisScript<Long> READ = new DefaultRedisScript<>("""
            local maximum = 0
            for _, key in ipairs(KEYS) do
                maximum = math.max(maximum, tonumber(redis.call('GET', key) or '0'))
            end
            return maximum
            """, Long.class);
    // Reserve before BCrypt. A burst must not pass separate reads before failures are recorded.
    // Failed/in-flight attempts consume slots; successful authentication clears its two budgets.
    private static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            for _, key in ipairs(KEYS) do
                if tonumber(redis.call('GET', key) or '0') >= 5 then return -1 end
            end
            for _, key in ipairs(KEYS) do
                local count = redis.call('INCR', key)
                if count == 1 then redis.call('EXPIRE', key, 900) end
            end
            return 0
            """, Long.class);

    public String sourceKey() {
        var attributes = RequestContextHolder.getRequestAttributes();
        String address = attributes instanceof ServletRequestAttributes servlet
                ? servlet.getRequest().getRemoteAddr() : "unknown";
        if (!trustedProxies.isBlank() && attributes instanceof ServletRequestAttributes servlet) {
            address = clientAddress(address, servlet.getRequest().getHeader("X-Forwarded-For"));
        }
        return "deployment:auth:source:" + digest(address);
    }

    private String clientAddress(String remote, String forwarded) {
        String direct = literalAddress(remote);
        if (direct == null) return remote;
        if (!trusted(direct) || forwarded == null || forwarded.isBlank() || forwarded.length() > 4096) return direct;
        String[] hops = forwarded.split(",", -1);
        if (hops.length > 32) return direct;
        // Validate the whole chain without resolving attacker-controlled host names.
        for (int i = 0; i < hops.length; i++) {
            hops[i] = literalAddress(hops[i].strip());
            if (hops[i] == null) return direct;
        }
        // The closest untrusted hop is the client. Ignore anything it supplied to its left.
        String current = direct;
        for (int i = hops.length - 1; i >= 0 && trusted(current); i--) current = hops[i];
        return current;
    }

    private boolean trusted(String address) {
        for (String configured : trustedProxies.split(",")) {
            configured = configured.strip();
            if (configured.isEmpty()) continue;
            String literal = literalAddress(configured);
            if (literal != null) {
                if (literal.equals(address)) return true;
            } else if (configured.matches("[a-zA-Z0-9][a-zA-Z0-9.-]*")) {
                // Only administrator-supplied service names (e.g. caddy) may use DNS.
                try {
                    for (InetAddress resolved : InetAddress.getAllByName(configured)) {
                        if (resolved.getHostAddress().equals(address)) return true;
                    }
                } catch (UnknownHostException unavailableProxy) { /* Do not trust an unresolved peer. */ }
            }
        }
        return false;
    }

    private static String literalAddress(String value) {
        if (value == null || value.isEmpty()) return null;
        if (value.contains(":")) {
            if (!value.matches("[0-9a-fA-F:.]+")) return null;
        } else {
            if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return null;
            for (String part : value.split("\\.")) if (Integer.parseInt(part) > 255) return null;
        }
        try { return InetAddress.getByName(value).getHostAddress(); }
        catch (UnknownHostException invalid) { return null; }
    }

    public String accountKey(Long userId, String account) {
        // SQL collation aliases (case, accents, spaces) must share one budget for an existing user.
        return "deployment:auth:account:" + (userId != null ? "id:" + userId
                : "unknown:" + digest(account == null ? "" : account.strip().toLowerCase(Locale.ROOT)));
    }

    public void check(String... keys) {
        Long count = redis.execute(READ, List.of(keys));
        if (count == null) throw new IllegalStateException("Authentication limit state unavailable");
        if (count >= 5) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "登录尝试过多，请 15 分钟后重试");
        }
    }

    public void reserve(String source, String account) {
        Long result = redis.execute(RESERVE, List.of(source, account));
        if (result == null) throw new IllegalStateException("Authentication limit state unavailable");
        if (result < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "登录尝试过多，请 15 分钟后重试");
        }
    }

    public void succeeded(String source, String account) {
        redis.delete(List.of(source, account));
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
