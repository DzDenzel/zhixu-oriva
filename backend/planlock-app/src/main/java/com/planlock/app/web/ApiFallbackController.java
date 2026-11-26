package com.planlock.app.web;

import com.planlock.common.ApiResponse;
import com.planlock.common.ErrorCode;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 未知 {@code /api/**} 确定性 JSON 404 兜底控制器（REQ-041 A1 / B-DEF-02）。
 * <p>
 * <ul>
 *   <li>将未匹配的 {@code /api/**} 路径与 Spring Boot 默认 {@code /**} 静态资源处理器彻底隔离，
 *       杜绝静态资源回退或 Whitelabel HTML 错误页；</li>
 *   <li>对于未认证的受保护 {@code /api/v1/**} 请求，{@code AuthInterceptor} 先于本控制器执行并由
 *       {@code GlobalExceptionHandler} 返回确定性 JSON {@code code=1001}（涵盖 {@code Accept: text/html}）；</li>
 *   <li>对于已认证或公开白名单下未知的 {@code /api/**} 请求，统一显式返回 HTTP 404 +
 *       {@code application/json} {@code ApiResponse(1003)}，不受 {@code Accept} 内容协商影响。</li>
 * </ul>
 */
@RestController
public class ApiFallbackController {

    @RequestMapping("/api/**")
    public ResponseEntity<ApiResponse<Void>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(ApiResponse.error(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getDefaultMessage()));
    }
}
