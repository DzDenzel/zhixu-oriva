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
 *   <li>三端（{@link ProtectedApiPreAuthFilter}、{@link JsonBodyLimitFilter}、{@link com.planlock.common.GlobalExceptionHandler}）
 *       均直接以 {@link HttpServletRequest} 驱动，经 {@link ServletRequestPathUtils#parseAndCache(HttpServletRequest)} 统一获取
 *       应用内路径（同步剥离 {@code contextPath} 与前缀型 {@code servletPath}），并按 {@link PathContainer.Options#HTTP_PATH}
 *       逐段剥离矩阵参数与分段 UTF-8 百分号解码（绝不将 {@code %2F} 展开为路径分隔符）；</li>
 *   <li>任何含可疑路径语法（{@code ..}、{@code ;}、{@code %}、{@code \}、{@code //}）的 API 变体均禁止获得公开白名单豁免，
 *       强制归类为受保护 API；</li>
 *   <li>公开产物通配（{@code /api/v1/plugins/artifacts/{official,third-party}/*}）与 MVC {@code {fileName:.+}} 保持一致，
 *       严格要求单个非空末段，绝不豁免空末段（如 {@code /api/v1/plugins/artifacts/official/}）或深层子路径。</li>
 * </ol>
 */
public final class ApiPathMatcher {

    private static final AntPathMatcher ANT_PATH_MATCHER = new AntPathMatcher();

    private ApiPathMatcher() {
    }

    /**
     * 解析与 Spring MVC 6.2 {@link ServletRequestPathUtils} 一致的应用内请求路径（剥离 contextPath 与前缀型 servletPath）。
     */
    public static String resolvePathWithinApplication(HttpServletRequest request) {
        if (request == null) {
            return "/";
        }
        try {
            RequestPath parsed = ServletRequestPathUtils.parseAndCache(request);
            if (parsed != null && parsed.pathWithinApplication() != null) {
                String value = parsed.pathWithinApplication().value();
                return (value == null || value.isEmpty()) ? "/" : value;
            }
        } catch (IllegalArgumentException ignored) {
            // 非法百分号编码时回退到容错路径剥离
        }
        return extractFallbackPathWithinApplication(request);
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
            if (parsed != null && isApiPathContainer(parsed.pathWithinApplication())) {
                return true;
            }
        } catch (IllegalArgumentException ignored) {
            // 非法百分号编码时回退到容错首段检测，避免漏判 API 前缀
        }
        return isApiPath(extractFallbackPathWithinApplication(request));
    }

    /**
     * 判断请求是否属于受保护（须携带有效 JWT）的 API 路由。
     */
    public static boolean isProtectedApiRequest(HttpServletRequest request) {
        if (request == null || !isApiRequest(request)) {
            return false;
        }
        String appPath = resolvePathWithinApplication(request);
        if (hasSuspiciousPublicPathToken(appPath)) {
            return true;
        }
        return !matchesPublicPattern(appPath);
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

    static String extractFallbackPathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "/";
        }
        String path = uri;
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        // 与 Spring ServletRequestPath 一致：当前缀映射 Servlet（pathInfo != null）存在时剥离 servletPath 前缀
        String pathInfo = request.getPathInfo();
        String servletPath = request.getServletPath();
        if (pathInfo != null && servletPath != null && !servletPath.isEmpty() && path.startsWith(servletPath)) {
            String stripped = path.substring(servletPath.length());
            path = stripped.isEmpty() ? "/" : stripped;
        }
        return path.isEmpty() ? "/" : path;
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
            if (publicPattern.endsWith("/*")) {
                // 严格对齐 Spring MVC {fileName:.+}：仅匹配单个非空末段，绝不豁免空末段 "/" 或多级路径
                String prefix = publicPattern.substring(0, publicPattern.length() - 1);
                if (cleanPath.startsWith(prefix)) {
                    String tail = cleanPath.substring(prefix.length());
                    if (!tail.isEmpty() && tail.indexOf('/') < 0) {
                        return true;
                    }
                }
            } else if (!publicPattern.contains("*") && !publicPattern.contains("?")) {
                if (publicPattern.equals(cleanPath)) {
                    return true;
                }
            } else if (ANT_PATH_MATCHER.match(publicPattern, cleanPath)) {
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
