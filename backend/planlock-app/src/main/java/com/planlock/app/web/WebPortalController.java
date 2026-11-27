package com.planlock.app.web;

import com.planlock.app.setup.SetupService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.charset.StandardCharsets;

/**
 * Web 数字站同源入口分流控制器（REQ-041 W1 / ADR-022 D1）。
 * <p>
 * 仅显式列举前端页面路由（严禁 {@code /**} 通配），从路由层杜绝任何未知 {@code /api/**} 路径回退为 HTML。
 */
@Controller
public class WebPortalController {

    static final String DEFAULT_WEB_INDEX_LOCATION = "web/index.html";
    static final String STRICT_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; "
            + "img-src 'self' data:; connect-src 'self'; font-src 'self'; form-action 'self'; "
            + "base-uri 'none'; frame-ancestors 'none'";
    private static final MediaType TEXT_HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);
    private static final byte[] FALLBACK_PLACEHOLDER_HTML = (
            "<!doctype html>\n"
            + "<html lang=\"zh-CN\">\n"
            + "<head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>知序 ORIVA · Web 数字站</title></head>\n"
            + "<body><main><h1>知序 ORIVA · Web 数字站</h1>"
            + "<p>后端同源基座已就绪。当前镜像尚未打包 Web 前端静态产物，请使用知序客户端连接本实例或升级至含 Web 数字站的发行版。</p>"
            + "</main></body>\n"
            + "</html>\n"
    ).getBytes(StandardCharsets.UTF_8);

    private final SetupService setupService;
    private final String webIndexLocation;

    @Autowired
    public WebPortalController(SetupService setupService) {
        this(setupService, DEFAULT_WEB_INDEX_LOCATION);
    }

    WebPortalController(SetupService setupService, String webIndexLocation) {
        this.setupService = setupService;
        this.webIndexLocation = webIndexLocation;
    }

    @GetMapping(value = {
            "/",
            "/today",
            "/plans",
            "/schedules",
            "/journal",
            "/rhythm",
            "/devices",
            "/plugins",
            "/login"
    }, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> portal() {
        boolean setupPending;
        try {
            setupPending = setupService.isSetupPending();
        } catch (Exception ignored) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Referrer-Policy", "no-referrer")
                    .header("X-Frame-Options", "DENY")
                    .header("Content-Security-Policy", STRICT_CSP)
                    .contentType(TEXT_HTML_UTF8)
                    .body(new ByteArrayResource("服务暂时不可用，请稍后重试。".getBytes(StandardCharsets.UTF_8)));
        }
        if (setupPending) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .header(HttpHeaders.LOCATION, "/setup")
                    .cacheControl(CacheControl.noStore())
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Referrer-Policy", "no-referrer")
                    .header("X-Frame-Options", "DENY")
                    .build();
        }
        ClassPathResource index = new ClassPathResource(webIndexLocation);
        Resource body = (index.exists() && index.isReadable())
                ? index
                : new ByteArrayResource(FALLBACK_PLACEHOLDER_HTML);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Frame-Options", "DENY")
                .header("Content-Security-Policy", STRICT_CSP)
                .contentType(TEXT_HTML_UTF8)
                .body(body);
    }
}
