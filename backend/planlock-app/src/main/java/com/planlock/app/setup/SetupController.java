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
     * 按 RFC 9110 §12.5.1 / §12.4.2 媒体类型与表示参数最具体匹配优先级评估是否接受 {@code text/html; charset=utf-8}（REQ-041 R3）：
     * <ul>
     *   <li>所有匹配层级均参与参数校验：{@code text/html}（基准 30）&gt; {@code text/*}（基准 20）&gt; {@code *&#47;*}（基准 10），
     *       同层带有效表示参数（如 {@code charset=utf-8}）优先于无参同级范围（+1）；携带与 UTF-8 HTML 表示不符的参数则视为不匹配；</li>
     *   <li>{@code text/html;q=0, *&#47;*;q=0.8}、{@code text/html;charset=utf-8;q=0, text/html;q=0.9} 或重复 Accept 头中同级显式 {@code q=0}
     *       均禁止被较宽泛范围掩盖；</li>
     *   <li>严格遵循 RFC 9110 §12.4.2 {@code qvalue = ("0" ["." 0*3DIGIT]) / ("1" ["." 0*3("0")])}，允许 {@code q=0.} / {@code q=1.}，
     *       拒绝 {@code q=1.5} / {@code q=0.1234} / {@code q=abc} 等非法权重。</li>
     * </ul>
     */
    static boolean acceptsHtml(List<String> acceptHeaders) {
        if (acceptHeaders == null || acceptHeaders.isEmpty()) {
            return true;
        }
        boolean hasNonBlankHeader = false;
        int bestSpecificity = 0;
        double bestQualityAtBest = 0.0;
        boolean explicitZeroAtBest = false;

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
                int specificity = htmlMatchSpecificity(mt);
                if (specificity == 0) {
                    continue;
                }
                if (specificity > bestSpecificity) {
                    bestSpecificity = specificity;
                    bestQualityAtBest = q;
                    explicitZeroAtBest = (q == 0.0);
                } else if (specificity == bestSpecificity) {
                    if (q == 0.0) {
                        explicitZeroAtBest = true;
                    }
                    if (q > bestQualityAtBest) {
                        bestQualityAtBest = q;
                    }
                }
            }
        }
        if (!hasNonBlankHeader) {
            return true;
        }
        return bestSpecificity > 0 && !explicitZeroAtBest && bestQualityAtBest > 0.0;
    }

    private static int htmlMatchSpecificity(MediaType mt) {
        String type = mt.getType();
        String subtype = mt.getSubtype();
        int baseTier;
        if ("text".equalsIgnoreCase(type) && "html".equalsIgnoreCase(subtype)) {
            baseTier = 3;
        } else if ("text".equalsIgnoreCase(type) && mt.isWildcardSubtype()) {
            baseTier = 2;
        } else if (mt.isWildcardType() && mt.isWildcardSubtype()) {
            baseTier = 1;
        } else {
            return 0;
        }

        // RFC 9110 §12.5.1：q 之前的参数均为媒体范围参数，须与实际表示 text/html; charset=utf-8 兼容；
        // q 之后的 accept-ext 参数不参与媒体范围匹配。
        int matchedParams = 0;
        for (Map.Entry<String, String> entry : mt.getParameters().entrySet()) {
            String key = entry.getKey();
            if ("q".equalsIgnoreCase(key)) {
                break;
            }
            if ("charset".equalsIgnoreCase(key)) {
                String normalizedCharset = unquoteParam(entry.getValue());
                if (!"utf-8".equalsIgnoreCase(normalizedCharset)) {
                    return 0;
                }
                matchedParams++;
            } else {
                // 实际 UTF-8 HTML 表示仅声明 charset=utf-8，不具备其他媒体范围参数
                return 0;
            }
        }
        return baseTier * 10 + matchedParams;
    }

    private static String unquoteParam(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }

    private static Double parseStrictQuality(MediaType mt) {
        String rawQ = mt.getParameter("q");
        if (rawQ == null) {
            return 1.0;
        }
        if (!rawQ.matches("(?:0(?:\\.[0-9]{0,3})?|1(?:\\.0{0,3})?)")) {
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
