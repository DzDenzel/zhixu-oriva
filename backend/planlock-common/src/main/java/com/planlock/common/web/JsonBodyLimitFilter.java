package com.planlock.common.web;

import com.planlock.common.ErrorCode;
import com.planlock.common.auth.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servlet 级 JSON 请求体预读拦截器：
 * <ol>
 *   <li>受保护 JSON 路由在读取/缓冲任何请求字节之前先行验证 Bearer JWT；</li>
 *   <li>零未记账临时读流数组：已知长度先 CAS 预留再直接读入单块定容数组；未知长度（chunked）在首个字节到达后按可用并发配额先预留再分配 {@code <= 8 KiB} 分段 slab 并直接读入，杜绝隐藏 scratch 数组与倍增峰值；</li>
 *   <li>非正总预算（{@code <= 0}）视为关闭并发限额，预留与释放对称跳过原子计数器更新，绝不产生负计数；路由上限锚定于 {@code Integer.MAX_VALUE - 8} 防溢出；</li>
 *   <li>结合大路由上限隔离与小载荷保底配额，防止慢速大包饿死公共登录与常规小请求；所有退出与异常/Error 路径均经 {@code finally} 精确归还。</li>
 * </ol>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class JsonBodyLimitFilter extends OncePerRequestFilter {

    private static final String SAFE_ERROR_JSON = "{\"code\":"
            + ErrorCode.INVALID_PARAM.getCode()
            + ",\"message\":\""
            + ErrorCode.INVALID_PARAM.getDefaultMessage()
            + "\"}";
    private static final String SAFE_UNAUTHORIZED_JSON = "{\"code\":"
            + ErrorCode.UNAUTHORIZED.getCode()
            + ",\"message\":\""
            + ErrorCode.UNAUTHORIZED.getDefaultMessage()
            + "\"}";
    static final int CHUNK_SIZE = 8192;
    static final int MAX_BUFFER_ARRAY_BYTES = Integer.MAX_VALUE - 8;

    private final JsonBodyLimitProperties properties;
    private final JwtUtil jwtUtil;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final AtomicLong activeBufferedBytes = new AtomicLong(0L);

    @Autowired
    public JsonBodyLimitFilter(JsonBodyLimitProperties properties, JwtUtil jwtUtil) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.jwtUtil = Objects.requireNonNull(jwtUtil, "jwtUtil must not be null");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isJsonRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        String path = normalizePath(request);
        if (isProtectedApiRoute(path) && !isAuthorized(request)) {
            log.warn("rejected unauthenticated json request before body read: method={}, uri={}",
                    request.getMethod(), request.getRequestURI());
            writeSafeJsonError(response, SAFE_UNAUTHORIZED_JSON);
            return;
        }
        int maxLimit = resolveBoundedLimit(path);
        if (maxLimit <= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        long contentLength = request.getContentLengthLong();
        boolean hasTransferEncoding = request.getHeader("Transfer-Encoding") != null;
        if (contentLength == 0L && !hasTransferEncoding) {
            filterChain.doFilter(request, response);
            return;
        }
        if (contentLength > maxLimit) {
            log.warn("rejected oversized request: category=payload_limit, stage=content_length, method={}, uri={}",
                    request.getMethod(), request.getRequestURI());
            writeSafeJsonError(response, SAFE_ERROR_JSON);
            return;
        }

        long effectiveMaxConcurrent = resolveEffectiveConcurrentLimit(maxLimit);
        long reserved = 0L;
        List<byte[]> segments = new ArrayList<>();
        byte[] currentSlab = null;
        int currentSlabOffset = 0;
        int total = 0;

        try {
            // Known positive Content-Length without Transfer-Encoding: reserve BEFORE allocating.
            if (contentLength > 0L && !hasTransferEncoding) {
                int preallocateCap = (int) contentLength;
                if (effectiveMaxConcurrent > 0L) {
                    if (!tryReserveConcurrentBytes(preallocateCap, effectiveMaxConcurrent)) {
                        log.warn("rejected request over concurrent json buffer budget: category=concurrent_payload_limit, stage=preallocate, method={}, uri={}",
                                request.getMethod(), request.getRequestURI());
                        writeSafeJsonError(response, SAFE_ERROR_JSON);
                        return;
                    }
                    reserved += preallocateCap;
                }
                currentSlab = new byte[preallocateCap];
                segments.add(currentSlab);
            }

            ServletInputStream in = request.getInputStream();
            while (true) {
                if (total == maxLimit) {
                    int extra = in.read();
                    if (extra == -1) {
                        break;
                    }
                    log.warn("rejected oversized request: category=payload_limit, stage=stream, method={}, uri={}",
                            request.getMethod(), request.getRequestURI());
                    writeSafeJsonError(response, SAFE_ERROR_JSON);
                    return;
                }
                if (currentSlab == null || currentSlabOffset >= currentSlab.length) {
                    int nextByte = in.read();
                    if (nextByte == -1) {
                        break;
                    }
                    int desiredSlab = Math.min(CHUNK_SIZE, maxLimit - total);
                    int slabCap;
                    if (effectiveMaxConcurrent > 0L) {
                        int granted = tryReserveAtMost(desiredSlab, effectiveMaxConcurrent);
                        if (granted <= 0) {
                            log.warn("rejected request over concurrent json buffer budget: category=concurrent_payload_limit, stage=stream, method={}, uri={}",
                                    request.getMethod(), request.getRequestURI());
                            writeSafeJsonError(response, SAFE_ERROR_JSON);
                            return;
                        }
                        reserved += granted;
                        slabCap = granted;
                    } else {
                        slabCap = desiredSlab;
                    }
                    currentSlab = new byte[slabCap];
                    currentSlabOffset = 0;
                    segments.add(currentSlab);
                    currentSlab[currentSlabOffset++] = (byte) nextByte;
                    total++;
                    continue;
                }
                int maxRead = Math.min(currentSlab.length - currentSlabOffset, maxLimit - total);
                int count = in.read(currentSlab, currentSlabOffset, maxRead);
                if (count == -1) {
                    break;
                }
                if (count == 0) {
                    continue;
                }
                currentSlabOffset += count;
                total += count;
            }

            if (total == 0) {
                currentSlab = null;
                segments.clear();
                releaseConcurrentBytes(reserved);
                reserved = 0L;
                filterChain.doFilter(request, response);
                return;
            }
            CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(
                    request, segments, total);
            filterChain.doFilter(wrapped, response);
        } catch (Exception ex) {
            if (hasPayloadLimitCause(ex) && !response.isCommitted()) {
                log.warn("rejected oversized request: category=payload_limit, stage=deserializer, method={}, uri={}",
                        request.getMethod(), request.getRequestURI());
                writeSafeJsonError(response, SAFE_ERROR_JSON);
                return;
            }
            throw ex;
        } finally {
            releaseConcurrentBytes(reserved);
        }
    }

    public static boolean hasPayloadLimitCause(Throwable throwable) {
        Throwable cursor = throwable;
        int depth = 0;
        while (cursor != null && depth < 16) {
            if (cursor instanceof JsonPayloadTooLargeException || cursor instanceof JsonCollectionTooLargeException) {
                return true;
            }
            cursor = cursor.getCause();
            depth++;
        }
        return false;
    }

    public long getActiveBufferedBytes() {
        return activeBufferedBytes.get();
    }

    long currentBufferedBytes() {
        return activeBufferedBytes.get();
    }

    long resolveEffectiveConcurrentLimit(long routeLimit) {
        long maxConcurrent = properties.getMaxConcurrentJsonBufferBytes();
        if (maxConcurrent <= 0L) {
            return 0L;
        }
        long defaultLimit = Math.min(properties.getMaxJsonRequestBytes(), MAX_BUFFER_ARRAY_BYTES);
        boolean isLargeRoute = defaultLimit > 0L && routeLimit > defaultLimit;
        if (!isLargeRoute) {
            return maxConcurrent;
        }
        long configuredReserve = properties.getSmallPayloadReserveBytes();
        if (configuredReserve <= 0L || configuredReserve >= maxConcurrent) {
            return maxConcurrent;
        }
        return maxConcurrent - configuredReserve;
    }

    int resolveBoundedLimit(String path) {
        long raw = resolveLimit(path);
        if (raw <= 0L) {
            return 0;
        }
        return (int) Math.min(raw, MAX_BUFFER_ARRAY_BYTES);
    }

    boolean isProtectedApiRoute(String path) {
        if (!pathMatcher.match("/api/v1/**", path)) {
            return false;
        }
        if (hasSuspiciousPublicPathToken(path)) {
            return true;
        }
        for (String publicPattern : WebConfig.PUBLIC_API_PATTERNS) {
            if (pathMatcher.match(publicPattern, path)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasSuspiciousPublicPathToken(String path) {
        return path.contains("..") || path.indexOf(';') >= 0 || path.indexOf('%') >= 0 || path.indexOf('\\') >= 0;
    }

    private boolean isAuthorized(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return false;
        }
        try {
            Claims claims = jwtUtil.parse(auth.substring(7));
            return claims.get("uid", Long.class) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean tryReserveConcurrentBytes(long delta, long maxConcurrent) {
        if (delta <= 0L || maxConcurrent <= 0L) {
            return false;
        }
        while (true) {
            long current = activeBufferedBytes.get();
            if (current > Long.MAX_VALUE - delta) {
                return false;
            }
            long next = current + delta;
            if (next > maxConcurrent) {
                return false;
            }
            if (activeBufferedBytes.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    private int tryReserveAtMost(int desired, long maxConcurrent) {
        if (desired <= 0 || maxConcurrent <= 0L) {
            return -1;
        }
        while (true) {
            long current = activeBufferedBytes.get();
            long available = maxConcurrent - current;
            if (available <= 0L) {
                return -1;
            }
            int grant = (int) Math.min(desired, available);
            if (current > Long.MAX_VALUE - grant) {
                return -1;
            }
            if (activeBufferedBytes.compareAndSet(current, current + grant)) {
                return grant;
            }
        }
    }

    private void releaseConcurrentBytes(long delta) {
        if (delta > 0L) {
            activeBufferedBytes.addAndGet(-delta);
        }
    }

    long resolveLimit(String path) {
        if (properties.getJsonRouteLimits() != null) {
            for (JsonBodyLimitProperties.RouteLimit rule : properties.getJsonRouteLimits()) {
                if (rule != null && rule.getPattern() != null && rule.getMaxBytes() > 0
                        && pathMatcher.match(rule.getPattern(), path)) {
                    return rule.getMaxBytes();
                }
            }
        }
        return properties.getMaxJsonRequestBytes();
    }

    private static String normalizePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "/";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private static boolean isJsonRequest(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return false;
        }
        try {
            MediaType parsed = MediaType.parseMediaType(contentType);
            String subtype = parsed.getSubtype().toLowerCase(Locale.ROOT);
            return "application".equalsIgnoreCase(parsed.getType())
                    && ("json".equals(subtype) || subtype.endsWith("+json"));
        } catch (IllegalArgumentException ignored) {
            return contentType.toLowerCase(Locale.ROOT).contains("json");
        }
    }

    private static void writeSafeJsonError(HttpServletResponse response, String body) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }

    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final List<byte[]> segments;
        private final int length;

        private CachedBodyHttpServletRequest(HttpServletRequest request, List<byte[]> segments, int length) {
            super(request);
            this.segments = segments;
            this.length = length;
        }

        @Override
        public int getContentLength() {
            return length;
        }

        @Override
        public long getContentLengthLong() {
            return length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new SegmentedServletInputStream(segments, length);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class SegmentedServletInputStream extends ServletInputStream {

        private final List<byte[]> segments;
        private final int totalLength;
        private int segmentIndex = 0;
        private int segmentOffset = 0;
        private int bytesRead = 0;

        private SegmentedServletInputStream(List<byte[]> segments, int totalLength) {
            this.segments = segments;
            this.totalLength = totalLength;
        }

        @Override
        public int read() {
            if (bytesRead >= totalLength) {
                return -1;
            }
            advanceSegmentIfNeeded();
            int value = segments.get(segmentIndex)[segmentOffset++] & 0xFF;
            bytesRead++;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            Objects.checkFromIndexSize(off, len, b.length);
            if (len == 0) {
                return 0;
            }
            if (bytesRead >= totalLength) {
                return -1;
            }
            int remainingTotal = totalLength - bytesRead;
            int toRead = Math.min(len, remainingTotal);
            int copied = 0;
            while (copied < toRead) {
                advanceSegmentIfNeeded();
                byte[] current = segments.get(segmentIndex);
                int availableInSegment = current.length - segmentOffset;
                int step = Math.min(toRead - copied, availableInSegment);
                System.arraycopy(current, segmentOffset, b, off + copied, step);
                segmentOffset += step;
                copied += step;
                bytesRead += step;
            }
            return copied;
        }

        @Override
        public int available() {
            return totalLength - bytesRead;
        }

        @Override
        public boolean isFinished() {
            return bytesRead >= totalLength;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // Replay stream backed by in-memory segments is always immediately readable.
        }

        private void advanceSegmentIfNeeded() {
            while (segmentIndex < segments.size() && segmentOffset >= segments.get(segmentIndex).length) {
                segmentIndex++;
                segmentOffset = 0;
            }
        }
    }
}
