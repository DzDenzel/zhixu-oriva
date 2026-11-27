package com.planlock.common.web;

import com.planlock.common.ErrorCode;
import com.planlock.common.auth.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 受保护 {@code /api/**} 前置匿名门禁过滤器（REQ-041 R1b，Argon 策略定案）：
 * <ul>
 *   <li>运行于 {@link JsonBodyLimitFilter} 与 Spring MVC {@code RequestMappingHandlerMapping} 之前；</li>
 *   <li>对所有非 {@link WebConfig#PUBLIC_API_PATTERNS} 的受保护 {@code /api/**} 路径（无论 HTTP 方法及是否携带
 *       {@code Content-Type: application/json}），若未携带有效 Bearer JWT，则在进入路由方法匹配（405）前统一返回
 *       {@code HTTP 200 JSON 1001 + Cache-Control: no-store + X-Content-Type-Options: nosniff}，防止通过方法探测枚举受保护端点；</li>
 *   <li>公开路由（health / setup / auth / 公开插件目录与下载）及已携带有效 JWT 的受保护请求完整放行进入后续过滤链与
 *       Spring MVC，保留原生 {@code 405 + Allow}、{@code OPTIONS}/{@code HEAD} 与认证后 {@code 404/1003} 契约；</li>
 *   <li>与 {@link JsonBodyLimitFilter} 完全解耦，绝不触碰或干扰 BUG-145 并发缓冲 CAS 预算计数与 EOF 逻辑。</li>
 * </ul>
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ProtectedApiPreAuthFilter extends OncePerRequestFilter {

    private static final String SAFE_UNAUTHORIZED_JSON = "{\"code\":"
            + ErrorCode.UNAUTHORIZED.getCode()
            + ",\"message\":\""
            + ErrorCode.UNAUTHORIZED.getDefaultMessage()
            + "\"}";

    private final JwtUtil jwtUtil;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Autowired
    public ProtectedApiPreAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = Objects.requireNonNull(jwtUtil, "jwtUtil must not be null");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String path = normalizePath(request);
        if (isProtectedApiRoute(path) && !isAuthorized(request)) {
            log.warn("rejected unauthenticated api request before dispatch: method={}, uri={}",
                    request.getMethod(), request.getRequestURI());
            writeUnauthorizedJson(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    boolean isProtectedApiRoute(String path) {
        if (!pathMatcher.match("/api/**", path)) {
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

    private static void writeUnauthorizedJson(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(SAFE_UNAUTHORIZED_JSON);
    }
}
