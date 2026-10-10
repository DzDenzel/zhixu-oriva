package com.planlock.common.web;

import com.planlock.common.auth.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置：注册认证拦截器；仅放行游客注册 / 验证码下发 / 注册 / 登录 / 备用密码找回 等公开端点。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/auth/device",
                        "/api/v1/auth/code/send",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/me/backup-password/reset",
                        "/api/v1/plugins/**"
                );
    }
}
