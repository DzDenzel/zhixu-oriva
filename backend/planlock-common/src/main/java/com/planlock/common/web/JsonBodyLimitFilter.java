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
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Servlet 级 JSON 请求体预读拦截器：
 * 先拦截已知超限 {@code Content-Length}，再通过计数输入流约束无 {@code Content-Length} 的流式/分块请求。
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
        BoundedJsonRequestWrapper wrapped = new BoundedJsonRequestWrapper(request, limit);
        try {
            filterChain.doFilter(wrapped, response);
        } catch (Exception ex) {
            if (hasPayloadLimitCause(ex) && !response.isCommitted()) {
                log.warn("rejected oversized request: category=payload_limit, stage=stream, method={}, uri={}",
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

    private static final class BoundedJsonRequestWrapper extends HttpServletRequestWrapper {

        private final long maxBytes;
        private ServletInputStream cachedInputStream;
        private BufferedReader cachedReader;

        private BoundedJsonRequestWrapper(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (cachedInputStream == null) {
                cachedInputStream = new BoundedServletInputStream(super.getInputStream(), maxBytes);
            }
            return cachedInputStream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            if (cachedReader == null) {
                String encoding = getCharacterEncoding();
                Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
                cachedReader = new BufferedReader(new InputStreamReader(getInputStream(), charset));
            }
            return cachedReader;
        }
    }

    private static final class BoundedServletInputStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long totalRead;

        private BoundedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (totalRead >= maxBytes) {
                int peek = delegate.read();
                if (peek != -1) {
                    throw new JsonPayloadTooLargeException();
                }
                return -1;
            }
            int next = delegate.read();
            if (next != -1) {
                totalRead++;
            }
            return next;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            long remaining = maxBytes - totalRead;
            int maxToRead = remaining >= Integer.MAX_VALUE ? len : (int) Math.min(len, Math.max(0L, remaining) + 1L);
            int count = delegate.read(b, off, maxToRead);
            if (count > 0) {
                totalRead += count;
                if (totalRead > maxBytes) {
                    throw new JsonPayloadTooLargeException();
                }
            }
            return count;
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            delegate.setReadListener(readListener);
        }
    }
}
