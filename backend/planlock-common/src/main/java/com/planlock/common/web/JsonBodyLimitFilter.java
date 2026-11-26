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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Servlet 级 JSON 请求体预读拦截器：
 * <ol>
 *   <li>受保护 JSON 路由在读取/缓冲任何请求字节之前先行验证 Bearer JWT；</li>
 *   <li>结合全局并发缓冲配额与按路由单请求上限，以单缓冲零冗余拷贝方式预读后交由下游重放。</li>
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
    private static final int CHUNK_SIZE = 8192;

    private final JsonBodyLimitProperties properties;
    private final JwtUtil jwtUtil;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final AtomicLong activeBufferedBytes = new AtomicLong(0L);

    @Autowired
    public JsonBodyLimitFilter(JsonBodyLimitProperties properties, JwtUtil jwtUtil) {
        this.properties = properties;
        this.jwtUtil = jwtUtil;
    }

    public JsonBodyLimitFilter(JsonBodyLimitProperties properties) {
        this(properties, null);
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
        long limit = resolveLimit(path);
        if (limit <= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        long contentLength = request.getContentLengthLong();
        boolean hasTransferEncoding = request.getHeader("Transfer-Encoding") != null;
        if (contentLength == 0L && !hasTransferEncoding) {
            filterChain.doFilter(request, response);
            return;
        }
        if (contentLength > limit) {
            log.warn("rejected oversized request: category=payload_limit, stage=content_length, method={}, uri={}",
                    request.getMethod(), request.getRequestURI());
            writeSafeJsonError(response, SAFE_ERROR_JSON);
            return;
        }
        long maxConcurrent = properties.getMaxConcurrentJsonBufferBytes();
        long reserved = 0L;
        int initialCapacity = contentLength > 0L ? (int) Math.min(contentLength, limit) : 256;
        DirectByteArrayOutputStream bufferOut = new DirectByteArrayOutputStream(initialCapacity);
        try {
            ServletInputStream in = request.getInputStream();
            byte[] chunk = new byte[CHUNK_SIZE];
            long total = 0L;
            while (true) {
                long remaining = limit - total;
                int maxRead = remaining >= chunk.length ? chunk.length : (int) remaining + 1;
                int count = in.read(chunk, 0, maxRead);
                if (count == -1) {
                    break;
                }
                total += count;
                if (total > limit) {
                    log.warn("rejected oversized request: category=payload_limit, stage=stream, method={}, uri={}",
                            request.getMethod(), request.getRequestURI());
                    writeSafeJsonError(response, SAFE_ERROR_JSON);
                    return;
                }
                if (!tryReserveConcurrentBytes(count, maxConcurrent)) {
                    log.warn("rejected request over concurrent json buffer budget: category=concurrent_payload_limit, method={}, uri={}",
                            request.getMethod(), request.getRequestURI());
                    writeSafeJsonError(response, SAFE_ERROR_JSON);
                    return;
                }
                reserved += count;
                bufferOut.write(chunk, 0, count);
            }
            if (bufferOut.size() == 0) {
                filterChain.doFilter(request, response);
                return;
            }
            CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(
                    request, bufferOut.rawBuffer(), bufferOut.size());
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

    long currentBufferedBytes() {
        return activeBufferedBytes.get();
    }

    private boolean isProtectedApiRoute(String path) {
        if (!pathMatcher.match("/api/v1/**", path)) {
            return false;
        }
        for (String publicPattern : WebConfig.PUBLIC_API_PATTERNS) {
            if (pathMatcher.match(publicPattern, path)) {
                return false;
            }
        }
        return true;
    }

    private boolean isAuthorized(HttpServletRequest request) {
        if (jwtUtil == null) {
            return true;
        }
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
            return true;
        }
        while (true) {
            long current = activeBufferedBytes.get();
            long next = current + delta;
            if (next > maxConcurrent) {
                return false;
            }
            if (activeBufferedBytes.compareAndSet(current, next)) {
                return true;
            }
        }
    }

    private void releaseConcurrentBytes(long delta) {
        if (delta > 0L) {
            activeBufferedBytes.addAndGet(-delta);
        }
    }

    private long resolveLimit(String path) {
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

    private static final class DirectByteArrayOutputStream extends ByteArrayOutputStream {

        private DirectByteArrayOutputStream(int initialCapacity) {
            super(initialCapacity);
        }

        private byte[] rawBuffer() {
            return this.buf;
        }
    }

    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] rawBuffer;
        private final int length;

        private CachedBodyHttpServletRequest(HttpServletRequest request, byte[] rawBuffer, int length) {
            super(request);
            this.rawBuffer = rawBuffer;
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
            return new CachedServletInputStream(rawBuffer, length);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class CachedServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream delegate;

        private CachedServletInputStream(byte[] rawBuffer, int length) {
            this.delegate = new ByteArrayInputStream(rawBuffer, 0, length);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return delegate.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // Replay stream backed by in-memory bytes is always immediately readable.
        }
    }
}
