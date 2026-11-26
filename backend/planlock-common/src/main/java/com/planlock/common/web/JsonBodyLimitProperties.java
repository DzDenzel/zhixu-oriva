package com.planlock.common.web;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * JSON 请求体前置字节上限配置：支持默认全局上限及按路由匹配覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "planlock.security")
public class JsonBodyLimitProperties {

    public static final long DEFAULT_MAX_JSON_REQUEST_BYTES = 65_536L;
    public static final long DEFAULT_JOURNAL_MAX_BYTES = 524_288L;
    public static final long DEFAULT_SYNC_PUSH_MAX_BYTES = 16_777_216L;
    public static final long DEFAULT_MAX_CONCURRENT_JSON_BUFFER_BYTES = 67_108_864L;
    public static final long DEFAULT_SMALL_PAYLOAD_RESERVE_BYTES = 4_194_304L;

    private long maxJsonRequestBytes = DEFAULT_MAX_JSON_REQUEST_BYTES;

    private long maxConcurrentJsonBufferBytes = DEFAULT_MAX_CONCURRENT_JSON_BUFFER_BYTES;

    /**
     * 为小载荷路由（<= maxJsonRequestBytes，含公共认证及普通接口）保留的并发缓冲保底配额。
     * 当总预算 <= 本值时自动退化为 0，避免小预算配置下误拒所有大路由请求。
     */
    private long smallPayloadReserveBytes = DEFAULT_SMALL_PAYLOAD_RESERVE_BYTES;

    private List<RouteLimit> jsonRouteLimits = new ArrayList<>(List.of(
            new RouteLimit("/api/v1/journals/**", DEFAULT_JOURNAL_MAX_BYTES),
            new RouteLimit("/api/v1/sync/push", DEFAULT_SYNC_PUSH_MAX_BYTES)
    ));

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteLimit {
        private String pattern;
        private long maxBytes;
    }
}
