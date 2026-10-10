package com.planlock.common.auth;

/**
 * 请求级用户上下文（ThreadLocal）。由 {@link AuthInterceptor} 写入，请求结束后清理。
 */
public final class UserContext {

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public record Principal(Long userId, Long deviceId) {
    }

    public static void set(Long userId, Long deviceId) {
        HOLDER.set(new Principal(userId, deviceId));
    }

    public static Principal get() {
        return HOLDER.get();
    }

    public static Long userId() {
        Principal p = HOLDER.get();
        return p == null ? null : p.userId();
    }

    public static Long deviceId() {
        Principal p = HOLDER.get();
        return p == null ? null : p.deviceId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
