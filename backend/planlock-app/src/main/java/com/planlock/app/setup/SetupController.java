package com.planlock.app.setup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
public class SetupController {
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
    @GetMapping(value="/setup",produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<ClassPathResource> page() {
        setup.requirePage();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer").header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy","default-src 'none'; script-src 'self'; style-src 'self'; img-src data:; connect-src 'self'; form-action 'self'; frame-ancestors 'none'")
                .body(new ClassPathResource("setup/index.html"));
    }
    @PostMapping("/api/v1/setup")
    public ResponseEntity<SetupService.SetupResult> initialize(@RequestBody SetupService.SetupRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(setup.initialize(request));
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> rejected(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode()).cacheControl(CacheControl.noStore()).body(Map.of("message",Objects.requireNonNullElse(error.getReason(),"请求无法完成")));
    }
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String,String>> malformed() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("message","请求格式无效，请检查输入"));
    }
}
