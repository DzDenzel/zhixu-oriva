package com.planlock.common.web;

import com.planlock.common.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

/**
 * Servlet 级 JSON 请求体预读拦截器：
 * 先拦截已知超限 {@code Content-Length}，再在调用 {@code filterChain.doFilter} 之前
 * 分块有界读取完整请求体；未超限才放入可重放请求体进入 Controller/事务。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
public class JsonBodyLimitFilter extends OncePerRequestFilter {

    private static final String SAFE_ERROR_JSON = "{\"code\":"
            + ErrorCode.INVALID_PARAM.getCode()
            + ",\"message\":\""
            + ErrorCode.INVALID_PARAM.getDefaultMessage()
            + "\"}";
    private static final int CHUNK_SIZE = 8192;

    private final JsonBodyLimitProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!isJsonRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        long limit = resolveLimit(request);
        if (limit <= 0) {
            filterChain.doFilter(request, response);
            return;
        }
        long contentLength = request.getContentLengthLong();
        if (contentLength > limit) {
            log.warn("rejected oversized request: category=payload_limit, stage=content_length, method={}, uri={}",
                    request.getMethod(), request.getRequestURI());
            writeSafeLimitError(response);
            return;
        }
        byte[] bodyBytes;
        try {
            bodyBytes = readBoundedBytes(request.getInputStream(), limit);
        } catch (JsonPayloadTooLargeException ex) {
            log.warn("rejected oversized request: category=payload_limit, stage=stream, method={}, uri={}",
                    request.getMethod(), request.getRequestURI());
            writeSafeLimitError(response);
            return;
        }
        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request, bodyBytes);
        try {
            filterChain.doFilter(wrapped, response);
        } catch (Exception ex) {
            if (hasPayloadLimitCause(ex) && !response.isCommitted()) {
                log.warn("rejected oversized request: category=payload_limit, stage=deserializer, method={}, uri={}",
                        request.getMethod(), request.getRequestURI());
                writeSafeLimitError(response);
                return;
            }
            throw ex;
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

    private static byte[] readBoundedBytes(ServletInputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[CHUNK_SIZE];
        long total = 0L;
        while (true) {
            long remaining = limit - total;
            int maxRead = remaining >= buffer.length ? buffer.length : (int) remaining + 1;
            int count = in.read(buffer, 0, maxRead);
            if (count == -1) {
                break;
            }
            total += count;
            if (total > limit) {
                throw new JsonPayloadTooLargeException();
            }
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private long resolveLimit(HttpServletRequest request) {
        String path = normalizePath(request);
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

    private static void writeSafeLimitError(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SAFE_ERROR_JSON);
    }

    private static final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        private CachedBodyHttpServletRequest(HttpServletRequest request, byte[] cachedBody) {
            super(request);
            this.cachedBody = cachedBody;
        }

        @Override
        public int getContentLength() {
            return cachedBody.length;
        }

        @Override
        public long getContentLengthLong() {
            return cachedBody.length;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedServletInputStream(cachedBody);
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

        private CachedServletInputStream(byte[] cachedBody) {
            this.delegate = new ByteArrayInputStream(cachedBody);
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
