package com.planlock.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.RequestPath;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.util.ServletRequestPathUtils;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * 与 Spring MVC 6.2 {@link ServletRequestPathUtils} / {@link PathContainer#parsePath} 完全对齐的 API 路径分类器（REQ-041 R1b）：
 * <ol>
 *   <li>通过 {@link PathContainer.Options#HTTP_PATH} 逐段剥离矩阵参数（{@code ;k=v}）并做分段 UTF-8 百分号解码
 *       （绝不将 {@code %2F} 展开为路径分隔符），同步识别前导多斜杠（如 {@code //api/v1/me}）、首段编码
 *       （如 {@code /%61pi/v1/me}）与首段/中段矩阵参数（如 {@code /api;v=1/v1/me}、{@code /api/v1;x=1/me}）；</li>
 *   <li>供 {@link ProtectedApiPreAuthFilter}、{@link JsonBodyLimitFilter} 与 {@link com.planlock.common.GlobalExceptionHandler}
 *       共享同一 API 前缀与受保护判定语义，彻底消除 Servlet 过滤器与 Spring MVC 路由解码差异；</li>
 *   <li>任何含可疑路径语法（{@code ..}、{@code ;}、{@code %}、{@code \}、{@code //}）的 API 变体均禁止获得公开白名单豁免，
 *       强制归类为受保护 API。</li>
 * </ol>
 */
public final class ApiPathMatcher {

    private static final AntPathMatcher ANT_PATH_MATCHER = new AntPathMatcher();

    private ApiPathMatcher() {
    }

    /**
     * 判断请求在 Spring MVC 6.2 路径语义下是否属于 {@code /api/**} 空间。
     */
    public static boolean isApiRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        try {
            RequestPath parsed = ServletRequestPathUtils.parseAndCache(request);
            if (isApiPathContainer(parsed.pathWithinApplication())) {
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            // 非法百分号编码时回退到容错首段检测，避免漏判 API 前缀
        }
        return isApiPath(extractRawPathWithinApplication(request));
    }

    /**
     * 判断请求是否属于受保护（须携带有效 JWT）的 API 路由。
     */
    public static boolean isProtectedApiRequest(HttpServletRequest request) {
        if (request == null || !isApiRequest(request)) {
            return false;
        }
        String rawPath = extractRawPathWithinApplication(request);
        if (hasSuspiciousPublicPathToken(rawPath)) {
            return true;
        }
        return !matchesPublicPattern(rawPath);
    }

    /**
     * 判断给定应用内路径字符串在 Spring MVC 6.2 路径语义下是否属于 {@code /api/**} 空间。
     */
    public static boolean isApiPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return false;
        }
        try {
            PathContainer container = PathContainer.parsePath(rawPath, PathContainer.Options.HTTP_PATH);
            if (isApiPathContainer(container)) {
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            // 畸形 % 编码时回退容错首段解析
        }
        return isApiFirstSegmentFallback(rawPath);
    }

    /**
     * 判断给定应用内路径字符串是否属于受保护（须携带有效 JWT）的 API 路由。
     */
    public static boolean isProtectedApiPath(String rawPath) {
        if (!isApiPath(rawPath)) {
            return false;
        }
        if (hasSuspiciousPublicPathToken(rawPath)) {
            return true;
        }
        return !matchesPublicPattern(rawPath);
    }

    static String extractRawPathWithinApplication(HttpServletRequest request) {
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

    static boolean hasSuspiciousPublicPathToken(String path) {
        return path.contains("..")
                || path.indexOf(';') >= 0
                || path.indexOf('%') >= 0
                || path.indexOf('\\') >= 0
                || path.contains("//");
    }

    private static boolean matchesPublicPattern(String cleanPath) {
        for (String publicPattern : WebConfig.PUBLIC_API_PATTERNS) {
            if (ANT_PATH_MATCHER.match(publicPattern, cleanPath)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isApiPathContainer(PathContainer container) {
        if (container == null) {
            return false;
        }
        for (PathContainer.Element element : container.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                return "api".equals(segment.valueToMatch());
            }
        }
        return false;
    }

    private static boolean isApiFirstSegmentFallback(String rawPath) {
        int start = 0;
        int len = rawPath.length();
        while (start < len && rawPath.charAt(start) == '/') {
            start++;
        }
        if (start >= len) {
            return false;
        }
        int end = rawPath.indexOf('/', start);
        String firstSegment = end < 0 ? rawPath.substring(start) : rawPath.substring(start, end);
        int semi = firstSegment.indexOf(';');
        if (semi >= 0) {
            firstSegment = firstSegment.substring(0, semi);
        }
        if ("api".equals(firstSegment)) {
            return true;
        }
        if (firstSegment.indexOf('%') >= 0 && firstSegment.indexOf('+') < 0) {
            try {
                String decoded = URLDecoder.decode(firstSegment, StandardCharsets.UTF_8);
                return "api".equals(decoded);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
        }
        return false;
    }
}
