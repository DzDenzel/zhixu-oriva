package com.planlock.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.PlanLockApplication;
import com.planlock.account.mapper.DeviceMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 MySQL + Flyway + MyBatis + Tomcat F37 端到端验证（关闭 REQ-041 包 A B-DEF-01/02/03 & A1/A2）。
 */
@EnabledIfEnvironmentVariable(named = "F37_ENABLED", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebPortalF37Test {

    private final String schema = "planlock_f37_web_" + UUID.randomUUID().toString().replace("-", "");
    private ConfigurableApplicationContext app;
    private ObjectMapper json;
    private boolean owned;

    private Connection connection(String database) throws Exception {
        return DriverManager.getConnection(TestDatabase.url(database),
                System.getenv("F37_DB_USER"), System.getenv("F37_DB_PASSWORD"));
    }

    @BeforeAll
    void setUp() throws Exception {
        if (!schema.matches("planlock_f37_web_[0-9a-f]{32}")) {
            throw new IllegalStateException("Unsafe schema");
        }
        try (var c = connection(""); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            owned = true;
        }
        try {
            app = new SpringApplication(PlanLockApplication.class).run(
                    "--spring.profiles.active=f37",
                    "--server.port=0",
                    "--planlock.deployment.enabled=false",
                    "--spring.datasource.url=" + TestDatabase.url(schema),
                    "--spring.datasource.username=" + System.getenv("F37_DB_USER"),
                    "--spring.datasource.password=" + System.getenv("F37_DB_PASSWORD"));
            json = app.getBean(ObjectMapper.class);
            PasswordEncoder encoder = app.getBean(PasswordEncoder.class);
            String hash = encoder.encode("f37-pass-123");
            try (var c = connection(schema);
                 var ps = c.prepareStatement("INSERT INTO users(id, email, password_hash) VALUES (?, ?, ?)")) {
                ps.setLong(1, 701L);
                ps.setString(2, "owner@f37.example");
                ps.setString(3, hash);
                ps.executeUpdate();

                ps.setLong(1, 702L);
                ps.setString(2, "member@f37.example");
                ps.setString(3, hash);
                ps.executeUpdate();
            }
            try (var c = connection(schema); var s = c.createStatement()) {
                s.execute("UPDATE deployment_instance SET initialized = TRUE, instance_label = 'F37实库实例', owner_user_id = 701 WHERE id = 1");
            }
        } catch (Exception | Error failure) {
            cleanup();
            throw failure;
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        if (app != null) {
            app.close();
            app = null;
        }
        if (owned) {
            try (var c = connection(""); var s = c.createStatement()) {
                s.execute("DROP DATABASE " + schema);
            }
            owned = false;
        }
    }

    @Test
    void realMyBatisLoginToMeAndAcceptMatrixWorkEndToEnd() throws Exception {
        int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
        String base = "http://127.0.0.1:" + port;
        HttpClient http = HttpClient.newHttpClient();

        // 1. Real POST /api/v1/auth/login with platform="web"
        String loginPayload = json.writeValueAsString(Map.of(
                "account", "owner@f37.example",
                "password", "f37-pass-123",
                "deviceUid", "web-f37-" + UUID.randomUUID(),
                "deviceName", "F37 Web Client",
                "platform", "web"
        ));
        HttpResponse<String> loginRes = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(loginPayload))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(loginRes.statusCode()).isEqualTo(200);
        var loginNode = json.readTree(loginRes.body());
        assertThat(loginNode.path("code").asInt()).isEqualTo(0);
        String token = loginNode.path("data").path("token").asText();
        long deviceId = loginNode.path("data").path("deviceId").asLong();

        // 2. Real GET /api/v1/me through real MyBatis UserMapper & DeviceMapper & JdbcTemplate DeploymentMetadataService
        HttpResponse<String> meRes = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/me"))
                        .header("Authorization", "Bearer " + token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(meRes.statusCode()).isEqualTo(200);
        var meNode = json.readTree(meRes.body());
        assertThat(meNode.path("code").asInt()).isEqualTo(0);
        assertThat(meNode.path("data").path("userId").asLong()).isEqualTo(701L);
        assertThat(meNode.path("data").path("deviceId").asLong()).isEqualTo(deviceId);
        assertThat(meNode.path("data").path("devicePlatform").asText()).isEqualTo("web");
        assertThat(meNode.path("data").path("accountMasked").asText()).isEqualTo("ow***r@f37.example");
        assertThat(meNode.path("data").path("instanceLabel").asText()).isEqualTo("F37实库实例");

        // 3. Delete device in MySQL -> GET /api/v1/me clears deviceId/deviceName/devicePlatform
        app.getBean(DeviceMapper.class).delete(deviceId);
        HttpResponse<String> afterUnbind = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/v1/me"))
                        .header("Authorization", "Bearer " + token)
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(afterUnbind.statusCode()).isEqualTo(200);
        var unbindNode = json.readTree(afterUnbind.body());
        assertThat(unbindNode.path("data").path("deviceId").isNull()).isTrue();

        // 4. Unknown /api/** across Accept: text/html and Accept: application/json
        for (String accept : List.of("text/html", "application/json", "*/*")) {
            HttpResponse<String> anon = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/not-exists"))
                            .header("Accept", accept).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(anon.statusCode()).isEqualTo(200);
            assertThat(json.readTree(anon.body()).path("code").asInt()).isEqualTo(1001);

            HttpResponse<String> authed = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/api/v1/not-exists"))
                            .header("Authorization", "Bearer " + token)
                            .header("Accept", accept).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(authed.statusCode()).isEqualTo(404);
            assertThat(json.readTree(authed.body()).path("code").asInt()).isEqualTo(1003);
        }
    }
}
