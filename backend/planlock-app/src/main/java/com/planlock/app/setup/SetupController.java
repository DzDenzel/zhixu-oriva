package com.planlock.app.setup;

import jakarta.servlet.http.HttpServletRequest;
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
    public ResponseEntity<ClassPathResource> page(HttpServletRequest request) {
        setup.requirePage();
        Enumeration<String> rawHeaders = request == null ? null : request.getHeaders(HttpHeaders.ACCEPT);
        List<String> acceptHeaders = rawHeaders == null ? List.of() : Collections.list(rawHeaders);
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
     * 按 RFC 9110 §12.5.1 / §12.4.2 直接在原始字符串层级解析 {@code Accept} 头并评估是否接受 {@code text/html; charset=utf-8}（REQ-041 R3）：
     * <ul>
     *   <li>先于任何 Map 归并、按引号感知语法切分逗号媒体范围与分号参数，彻底消除 Spring {@code MediaType} 同名参数覆盖吞掉重复 {@code q=} 的隐患；</li>
     *   <li>同一媒体范围若出现超过 1 个 {@code q} 参数或任一 {@code q} 不满足 {@code (?:0(?:\.[0-9]{0,3})?|1(?:\.0{0,3})?)}，
     *       立即整体判 406 拒绝（绝不被 {@code *&#47;*} 掩盖）；</li>
     *   <li>按 RFC 9110 识别任意位置（不限于 {@code q} 之前）的全部表示参数并与 {@code text/html; charset=utf-8} 统一比对：
     *       {@code text/html}（基准 30）&gt; {@code text/*}（基准 20）&gt; {@code *&#47;*}（基准 10），匹配 {@code charset=utf-8} 具体度 +1，
     *       出现不兼容字符集或未声明参数则该范围不匹配 HTML 表示；</li>
     *   <li>同具体度存在显式 {@code q=0} 时优先拒绝；已初始化状态始终在调用本方法前先返 410 JSON。</li>
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
            List<String> rawRanges = splitRespectingQuotes(header, ',');
            if (rawRanges == null) {
                return false;
            }
            boolean hasRangeInHeader = false;
            for (String rawRange : rawRanges) {
                String trimmedRange = rawRange.trim();
                if (trimmedRange.isEmpty()) {
                    continue;
                }
                hasRangeInHeader = true;
                List<String> parts = splitRespectingQuotes(trimmedRange, ';');
                if (parts == null || parts.isEmpty()) {
                    return false;
                }
                String typePart = parts.get(0).trim();
                int slash = typePart.indexOf('/');
                if (slash <= 0 || slash != typePart.lastIndexOf('/') || slash == typePart.length() - 1) {
                    return false;
                }
                String type = typePart.substring(0, slash).trim();
                String subtype = typePart.substring(slash + 1).trim();
                if (!isHttpToken(type) || !isHttpToken(subtype)) {
                    return false;
                }
                if ("*".equals(type) && !"*".equals(subtype)) {
                    return false;
                }

                boolean seenQ = false;
                double q = 1.0;
                Set<String> matchedParams = new HashSet<>();
                boolean paramMismatch = false;

                for (int i = 1; i < parts.size(); i++) {
                    String rawParam = parts.get(i).trim();
                    if (rawParam.isEmpty()) {
                        continue;
                    }
                    int eq = rawParam.indexOf('=');
                    if (eq <= 0 || eq == rawParam.length() - 1) {
                        return false;
                    }
                    String paramName = rawParam.substring(0, eq).trim();
                    String rawParamValue = rawParam.substring(eq + 1).trim();
                    if (!isHttpToken(paramName) || rawParamValue.isEmpty()) {
                        return false;
                    }

                    if ("q".equalsIgnoreCase(paramName)) {
                        // RFC 9110 §12.4.2：单媒体范围严禁重复 q 参数，且 qvalue 不可带引号
                        if (seenQ) {
                            return false;
                        }
                        Double parsedQ = parseStrictQualityValue(rawParamValue);
                        if (parsedQ == null) {
                            return false;
                        }
                        q = parsedQ;
                        seenQ = true;
                        continue;
                    }

                    String unquotedValue = parseParamValue(rawParamValue);
                    if (unquotedValue == null) {
                        return false;
                    }
                    // RFC 9110 §12.5.1：任意位置（含 q 之后）的非 q 参数均按实际表示 text/html; charset=utf-8 参与匹配
                    if ("charset".equalsIgnoreCase(paramName)) {
                        if ("utf-8".equalsIgnoreCase(unquotedValue.trim())) {
                            matchedParams.add("charset");
                        } else {
                            paramMismatch = true;
                        }
                    } else {
                        paramMismatch = true;
                    }
                }

                if (paramMismatch) {
                    continue;
                }
                int baseTier;
                if ("text".equalsIgnoreCase(type) && "html".equalsIgnoreCase(subtype)) {
                    baseTier = 3;
                } else if ("text".equalsIgnoreCase(type) && "*".equals(subtype)) {
                    baseTier = 2;
                } else if ("*".equals(type) && "*".equals(subtype)) {
                    baseTier = 1;
                } else {
                    continue;
                }
                int specificity = baseTier * 10 + matchedParams.size();
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
            if (!hasRangeInHeader) {
                return false;
            }
        }
        if (!hasNonBlankHeader) {
            return true;
        }
        return bestSpecificity > 0 && !explicitZeroAtBest && bestQualityAtBest > 0.0;
    }

    /** 按 RFC 9110 双引号参数值感知切分分隔符；遇未闭合引号或悬空转义返回 {@code null}。 */
    private static List<String> splitRespectingQuotes(String input, char delimiter) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (inQuotes) {
                if (c == '\\') {
                    current.append(c);
                    escaped = true;
                } else if (c == '"') {
                    current.append(c);
                    inQuotes = false;
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    current.append(c);
                    inQuotes = true;
                } else if (c == delimiter) {
                    segments.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        if (inQuotes || escaped) {
            return null;
        }
        segments.add(current.toString());
        return segments;
    }

    private static String parseParamValue(String raw) {
        if (raw.startsWith("\"")) {
            if (raw.length() < 2 || !raw.endsWith("\"")) {
                return null;
            }
            StringBuilder out = new StringBuilder(raw.length() - 2);
            boolean escaped = false;
            for (int i = 1; i < raw.length() - 1; i++) {
                char c = raw.charAt(i);
                if (escaped) {
                    out.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    return null;
                } else {
                    out.append(c);
                }
            }
            return escaped ? null : out.toString();
        }
        return isHttpToken(raw) ? raw : null;
    }

    private static boolean isHttpToken(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private static Double parseStrictQualityValue(String rawQ) {
        if (rawQ == null || !rawQ.matches("(?:0(?:\\.[0-9]{0,3})?|1(?:\\.0{0,3})?)")) {
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
