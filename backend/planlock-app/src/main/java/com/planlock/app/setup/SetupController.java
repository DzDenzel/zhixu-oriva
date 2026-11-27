package com.planlock.app.setup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
public class SetupController {
    private static final MediaType TEXT_HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);
    private final SetupService setup;
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final String version;
    public SetupController(SetupService setup,JdbcTemplate jdbc,StringRedisTemplate redis,
            @Value("${planlock.version:2.1.0}") String version) {
        this.setup=setup; this.jdbc=jdbc; this.redis=redis; this.version=version;
    }
    @GetMapping("/api/v1/health")
    public ResponseEntity<Map<String,Object>> health() {
        var body=new LinkedHashMap<String,Object>();
        body.put("status","DOWN"); body.put("version",version); body.put("migrationVersion",null);
        body.put("instanceId",null); body.put("initialized",false);
        boolean healthy=false;
        try {
            jdbc.queryForObject("SELECT 1",Integer.class);
            var state=setup.state(); body.put("instanceId",state.get("instance_id")); body.put("initialized",setup.initialized(state));
            body.put("migrationVersion",jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1",String.class));
            try(var connection=Objects.requireNonNull(redis.getConnectionFactory()).getConnection()) { healthy="PONG".equals(connection.ping()); }
        } catch (Exception ignored) { /* Public readiness never exposes dependency exceptions or connection strings. */ }
        if(healthy) body.put("status","UP");
        return ResponseEntity.status(healthy?HttpStatus.OK:HttpStatus.SERVICE_UNAVAILABLE).cacheControl(CacheControl.noStore()).body(body);
    }
    @GetMapping("/setup")
    public ResponseEntity<ClassPathResource> page(@RequestHeader(value = HttpHeaders.ACCEPT, required = false) List<String> acceptHeaders) {
        setup.requirePage();
        if (!acceptsHtml(acceptHeaders)) {
            throw new ResponseStatusException(HttpStatus.NOT_ACCEPTABLE, "该页面仅支持 text/html 响应");
        }
        return ResponseEntity.ok()
                .contentType(TEXT_HTML_UTF8)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy","no-referrer")
                .header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src data:; connect-src 'self'; form-action 'self'; frame-ancestors 'none'")
                .body(new ClassPathResource("setup/index.html"));
    }
    @PostMapping("/api/v1/setup")
    public ResponseEntity<SetupService.SetupResult> initialize(@RequestBody SetupService.SetupRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(setup.initialize(request));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> rejected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("message",Objects.requireNonNullElse(error.getReason(),"请求无法完成")));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,String>> malformed() {
        return ResponseEntity.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .body(Map.of("message","请求格式无效，请检查输入"));
    }
    /**
     * 按 RFC 9110 §12.5.1 媒体类型最具体匹配优先级评估是否接受 {@code text/html}（REQ-041 R3）：
     * <ul>
     *   <li>精确匹配 {@code text/html}（Tier 3）优先于 {@code text/*}（Tier 2），{@code text/*} 优先于 {@code *&#47;*}（Tier 1）；</li>
     *   <li>{@code text/html;q=0, *&#47;*;q=0.8} 或重复 Accept 头中显式 {@code q=0} 均禁止被通配掩盖；</li>
     *   <li>任意无效语法或越界 {@code q} 权重均按 406 拒绝。</li>
     * </ul>
     */
    static boolean acceptsHtml(List<String> acceptHeaders) {
        if (acceptHeaders == null || acceptHeaders.isEmpty()) {
            return true;
        }
        boolean hasNonBlankHeader = false;
        int bestTier = 0;
        double bestQualityAtTier = 0.0;
        boolean explicitZeroAtBestTier = false;

        for (String header : acceptHeaders) {
            if (header == null || header.isBlank()) {
                continue;
            }
            hasNonBlankHeader = true;
            List<MediaType> parsed;
            try {
                parsed = MediaType.parseMediaTypes(header);
            } catch (IllegalArgumentException invalid) {
                return false;
            }
            if (parsed.isEmpty()) {
                return false;
            }
            for (MediaType mt : parsed) {
                Double q = parseStrictQuality(mt);
                if (q == null) {
                    return false;
                }
                int tier = htmlMatchTier(mt);
                if (tier == 0) {
                    continue;
                }
                if (tier > bestTier) {
                    bestTier = tier;
                    bestQualityAtTier = q;
                    explicitZeroAtBestTier = (q == 0.0);
                } else if (tier == bestTier) {
                    if (q == 0.0) {
                        explicitZeroAtBestTier = true;
                    }
                    if (q > bestQualityAtTier) {
                        bestQualityAtTier = q;
                    }
                }
            }
        }
        if (!hasNonBlankHeader) {
            return true;
        }
        return bestTier > 0 && !explicitZeroAtBestTier && bestQualityAtTier > 0.0;
    }

    private static int htmlMatchTier(MediaType mt) {
        String type = mt.getType();
        String subtype = mt.getSubtype();
        if ("text".equalsIgnoreCase(type) && "html".equalsIgnoreCase(subtype)) {
            String charset = mt.getParameter("charset");
            if (charset != null && !"utf-8".equalsIgnoreCase(charset.trim())) {
                return 0;
            }
            for (String paramKey : mt.getParameters().keySet()) {
                if (!"q".equalsIgnoreCase(paramKey) && !"charset".equalsIgnoreCase(paramKey)) {
                    return 0;
                }
            }
            return 3;
        }
        if ("text".equalsIgnoreCase(type) && mt.isWildcardSubtype()) {
            return 2;
        }
        if (mt.isWildcardType() && mt.isWildcardSubtype()) {
            return 1;
        }
        return 0;
    }

    private static Double parseStrictQuality(MediaType mt) {
        String rawQ = mt.getParameter("q");
        if (rawQ == null) {
            return 1.0;
        }
        if (!rawQ.matches("(?:0(?:\\.[0-9]{1,3})?|1(?:\\.0{1,3})?)")) {
            return null;
        }
        try {
            double val = Double.parseDouble(rawQ);
            return (val >= 0.0 && val <= 1.0) ? val : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
