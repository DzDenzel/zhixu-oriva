package com.planlock.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.PlanLockApplication;
import com.planlock.common.auth.JwtUtil;
import com.planlock.record.service.RecordService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP/MySQL contracts: owner isolation, cursors, legacy keys and plan metadata. */
@EnabledIfEnvironmentVariable(named = "F37_ENABLED", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionHistoryContractTest {
    private final String schema = "planlock_f101_" + UUID.randomUUID().toString().replace("-", "");
    private ConfigurableApplicationContext app;
    private ObjectMapper json;
    private String baseUrl;
    private boolean owned;

    private Connection connection(String database) throws Exception {
        return DriverManager.getConnection(TestDatabase.url(database), System.getenv("F37_DB_USER"), System.getenv("F37_DB_PASSWORD"));
    }

    @BeforeAll void setup() throws Exception {
        if (!schema.matches("planlock_f101_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe schema");
        try (var c = connection(""); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            owned = true;
        }
        try {
            app = new SpringApplication(PlanLockApplication.class).run("--spring.profiles.active=f37", "--server.port=0",
                    "--spring.datasource.url=" + TestDatabase.url(schema),
                    "--spring.datasource.username=" + System.getenv("F37_DB_USER"),
                    "--spring.datasource.password=" + System.getenv("F37_DB_PASSWORD"));
            sql("ALTER TABLE execution_records MODIFY client_id VARCHAR(64) NULL");
            sql("ALTER TABLE unlock_records MODIFY client_id VARCHAR(64) NULL");
            for (long user = 201; user <= 218; user++) sql("INSERT INTO users(id) VALUES (" + user + ")");
            json = app.getBean(ObjectMapper.class);
            baseUrl = "http://127.0.0.1:" + ((ServletWebServerApplicationContext) app).getWebServer().getPort() + "/api/v1";
        } catch (Exception | Error failure) { cleanup(); throw failure; }
    }

    @AfterAll void cleanup() throws Exception {
        if (app != null) { app.close(); app = null; }
        if (owned) {
            try (var c = connection(""); var s = c.createStatement()) { s.execute("DROP DATABASE " + schema); owned = false; }
        }
    }

    private void sql(String text) throws Exception {
        try (var c = connection(schema); var s = c.createStatement()) { s.execute(text); }
    }

    private JsonNode request(long user, String method, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + app.getBean(JwtUtil.class).generate(user, 1L))
                .header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode result = json.readTree(response.body());
        assertThat(result.path("code").asInt()).as(response.body()).isZero();
        return result.path("data");
    }

    private String plan(String extra) {
        return "{\"name\":\"history test\",\"type\":\"学习\",\"startTime\":\"09:00:00\",\"endTime\":\"10:00:00\"" + extra + "}";
    }

    @Test void scheduleLocationCoordinatesSurviveCrudAndSyncAndLegacyTextStaysNullable() throws Exception {
        String body="{\"title\":\"见面地点\",\"startDate\":\"2026-10-19\",\"endDate\":\"2026-10-19\",\"allDay\":true,\"location\":\"人民广场\",\"locationLat\":31.2304,\"locationLon\":121.4737}";
        var created=request(217,"POST","/schedules",body);
        long id=created.path("id").asLong();
        assertThat(created.path("locationLat").asDouble()).isEqualTo(31.2304);
        assertThat(request(217,"GET","/sync/pull",null).path("schedules").get(0).path("locationLon").asDouble()).isEqualTo(121.4737);
        request(217,"PUT","/schedules/"+id+"?baseVersion=0",body.replace("31.2304","31.2404"));
        request(217,"POST","/sync/push","{\"schedules\":["+body.replace("31.2304","31.2504").replace("{\"title\"","{\"id\":"+id+",\"baseVersion\":1,\"title\"")+"]}");
        assertThat(request(217,"GET","/schedules",null).get(0).path("locationLat").asDouble()).isEqualTo(31.2504);
        var legacy=request(218,"POST","/schedules",body.replace(",\"locationLat\":31.2304,\"locationLon\":121.4737",""));
        assertThat(legacy.path("location").asText()).isEqualTo("人民广场");
        assertThat(legacy.path("locationLat").isNull()).isTrue();
    }

    @Test void guestMergeTransfersSchedulesTogetherWithPlansAndIsIdempotent() throws Exception {
        long planId = request(213, "POST", "/plans", plan("")).path("id").asLong();
        long scheduleId = request(213, "POST", "/schedules", "{\"title\":\"guest event\",\"startDate\":\"2026-10-18\",\"endDate\":\"2026-10-18\",\"allDay\":true,\"tzId\":\"Asia/Shanghai\"}").path("id").asLong();
        assertThat(scheduleId).isPositive();
        String mergeBody = "{\"guestUserId\":213,\"guestToken\":" + json.writeValueAsString(app.getBean(JwtUtil.class).generate(213L, 1L)) + "}";
        var merged = request(214, "POST", "/auth/merge", mergeBody);
        assertThat(merged.path("mergedSchedules").asInt()).isEqualTo(1);
        var snapshot = request(214, "GET", "/sync/pull", null);
        assertThat(snapshot.path("plans").findValuesAsText("id")).contains(Long.toString(planId));
        assertThat(snapshot.path("schedules").findValuesAsText("id")).contains(Long.toString(scheduleId));
        assertThat(request(213, "GET", "/sync/pull", null).path("schedules")).isEmpty();
        assertThat(request(214, "POST", "/auth/merge", mergeBody).path("mergedSchedules").asInt()).isZero();
    }

    @Test void mergeCannotClaimAnotherAccountOrUnprovenGuest() throws Exception {
        sql("UPDATE users SET email='merge-protected@example.invalid' WHERE id=216");
        request(216, "POST", "/plans", plan(""));
        for (String body : new String[]{"{\"guestUserId\":216}",
                "{\"guestUserId\":213}",
                "{\"guestUserId\":213,\"guestToken\":" + json.writeValueAsString(app.getBean(JwtUtil.class).generate(213L,2L)) + "}",
                "{\"guestUserId\":216,\"guestToken\":" + json.writeValueAsString(app.getBean(JwtUtil.class).generate(216L,1L)) + "}"}) {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/auth/merge"))
                    .header("Authorization", "Bearer " + app.getBean(JwtUtil.class).generate(215L, 1L))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(json.readTree(response.body()).path("code").asInt()).as("merge source rejected").isEqualTo(1001);
        }
        assertThat(request(216,"GET","/plans",null)).hasSize(1);
        assertThat(request(215,"GET","/plans",null)).isEmpty();
    }

    @Test void rejectsMalformedOrOversizedStatusLogsBeforeEitherWritePath() throws Exception {
        String tooMany = "[" + java.util.stream.IntStream.range(0, 51)
                .mapToObj(i -> "{\"date\":\"2026-10-16\",\"status\":1}").collect(java.util.stream.Collectors.joining(",")) + "]";
        for (String log : new String[]{"{}", "[] trailing", "[{\"date\":\"2026-02-30\",\"status\":1}]",
                "[{\"date\":\"2026-10-16\",\"status\":2}]", "[{\"date\":\"2026-10-16\",\"status\":\"1\"}]", tooMany}) {
            String body = plan(",\"statusLog\":" + json.writeValueAsString(log));
            var builder = HttpRequest.newBuilder(URI.create(baseUrl + "/plans"))
                    .header("Authorization", "Bearer " + app.getBean(JwtUtil.class).generate(208L, 1L))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            var response = HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(json.readTree(response.body()).path("code").asInt()).as(log)
                    .isEqualTo(com.planlock.common.ErrorCode.INVALID_PARAM.getCode());

            var sync = request(208, "POST", "/sync/push", "{\"plans\":[" + body + "]}").path("results").get(0);
            assertThat(sync.path("code").asInt()).as("sync: " + log)
                    .isEqualTo(com.planlock.common.ErrorCode.INVALID_PARAM.getCode());
        }
        assertThat(request(208, "GET", "/plans", null)).isEmpty();
        String fifty = "[" + java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> "{\"date\":\"2026-10-16\",\"status\":1}").collect(java.util.stream.Collectors.joining(",")) + "]";
        var accepted = request(209, "POST", "/plans", plan(",\"statusLog\":" + json.writeValueAsString(fifty)));
        assertThat(json.readTree(accepted.path("statusLog").asText())).hasSize(50);
    }

    @Test void planMetadataRoundTripsAcrossCrudSyncAndLegacyUpdates() throws Exception {
        String log = "[{\"date\":\"2026-10-16\",\"status\":0}]";
        String extra = ",\"endedOccurrenceDate\":\"2026-10-16\",\"statusLog\":" + json.writeValueAsString(log);
        var created = request(201, "POST", "/plans", plan(extra));
        assertThat(created.path("endedOccurrenceDate").asText()).isEqualTo("2026-10-16");
        assertThat(json.readTree(created.path("statusLog").asText())).isEqualTo(json.readTree(log));
        long id = created.path("id").asLong();
        var legacy = request(201, "PUT", "/plans/" + id + "?baseVersion=0", plan(""));
        assertThat(legacy.path("endedOccurrenceDate").asText()).isEqualTo("2026-10-16");
        assertThat(json.readTree(legacy.path("statusLog").asText())).isEqualTo(json.readTree(log));

        request(201, "POST", "/sync/push", "{\"plans\":[" + plan(",\"id\":" + id + ",\"baseVersion\":1,\"status\":0") + "]}");
        var pulled = request(201, "GET", "/sync/pull", null).path("plans").get(0);
        assertThat(pulled.path("endedOccurrenceDate").asText()).isEqualTo("2026-10-16");
        assertThat(json.readTree(pulled.path("statusLog").asText())).isEqualTo(json.readTree(log));

        request(201, "POST", "/sync/push", "{\"plans\":[" + plan(",\"id\":" + id + ",\"baseVersion\":2,\"endedOccurrenceDate\":\"2026-10-17\",\"statusLog\":\"[]\"") + "]}");
        var finalPlan = request(201, "GET", "/plans", null).get(0);
        assertThat(finalPlan.path("endedOccurrenceDate").asText()).isEqualTo("2026-10-17");
        assertThat(finalPlan.path("statusLog").asText()).isEqualTo("[]");
        var paused = request(201, "POST", "/plans/" + id + "/pause", null);
        assertThat(paused.path("endedOccurrenceDate").asText()).isEqualTo("2026-10-17");
        assertThat(json.readTree(paused.path("statusLog").asText()).get(0).path("status").asInt()).isZero();
        request(210, "POST", "/sync/push", "{\"plans\":[" + plan(extra) + "]}");
        var syncCreated = request(210, "GET", "/sync/pull", null).path("plans").get(0);
        assertThat(syncCreated.path("endedOccurrenceDate").asText()).isEqualTo("2026-10-16");
        assertThat(json.readTree(syncCreated.path("statusLog").asText())).isEqualTo(json.readTree(log));
        assertThat(request(202, "GET", "/sync/pull", null).path("plans")).isEmpty();
    }

    @Test void pauseAppendsExplicitUserDateAndKeepsOnlyTheLatestFiftyEntries() throws Exception {
        String entries = "[" + java.util.stream.IntStream.range(0, 50)
                .mapToObj(i -> "{\"date\":\"2026-10-16\",\"status\":1}").collect(java.util.stream.Collectors.joining(",")) + "]";
        var created = request(212, "POST", "/plans", plan(",\"statusLog\":" + json.writeValueAsString(entries)));
        var paused = request(212, "POST", "/plans/" + created.path("id").asLong() + "/pause?date=2026-10-17", null);
        var log = json.readTree(paused.path("statusLog").asText());
        assertThat(log).hasSize(50);
        assertThat(log.get(49).path("date").asText()).isEqualTo("2026-10-17");
        assertThat(log.get(49).path("status").asInt()).isZero();
        assertThat(paused.path("version").asLong()).isEqualTo(1);
        var resumed = request(212, "POST", "/plans/" + created.path("id").asLong() + "/pause?date=2026-10-18", null);
        assertThat(json.readTree(resumed.path("statusLog").asText()).get(49).path("status").asInt()).isEqualTo(1);
        assertThat(resumed.path("version").asLong()).isEqualTo(2);
    }

    @Test void sameOwnerUploadsCannotCommitHigherIdsAheadOfUncommittedLowerIds() throws Exception {
        long planId = request(211, "POST", "/plans", plan("")).path("id").asLong();
        for (String kind : new String[]{"execution", "unlock"}) {
            var barrier = new UploadCommitBarrier(kind);
            app.getBean(org.apache.ibatis.session.SqlSessionFactory.class).getConfiguration().addInterceptor(barrier);
            try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> {
                    Thread.currentThread().setName("f101-first-" + kind);
                    upload(kind, planId, "concurrent-first-" + kind);
                });
                assertThat(barrier.firstInserted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> {
                    Thread.currentThread().setName("f101-second-" + kind);
                    barrier.secondStarted.countDown();
                    upload(kind, planId, "concurrent-second-" + kind);
                });
                assertThat(barrier.secondStarted.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(500, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                var visible = request(211, "GET", "/records/history", null).path(kind.equals("execution") ? "executions" : "unlocks");
                assertThat(visible).isEmpty();
                barrier.allowFirstCommit.countDown();
                first.get(10, java.util.concurrent.TimeUnit.SECONDS);
                second.get(10, java.util.concurrent.TimeUnit.SECONDS);
                var committed = request(211, "GET", "/records/history", null).path(kind.equals("execution") ? "executions" : "unlocks");
                assertThat(committed).hasSize(2);
                assertThat(committed.get(0).path("clientId").asText()).isEqualTo("concurrent-first-" + kind);
                assertThat(committed.get(1).path("clientId").asText()).isEqualTo("concurrent-second-" + kind);
            } finally { barrier.allowFirstCommit.countDown(); barrier.active = false; }
        }
    }

    private void upload(String kind, long planId, String key) {
        var service = app.getBean(RecordService.class);
        if (kind.equals("execution")) {
            var request = new com.planlock.record.dto.ExecutionRecordRequest();
            request.setPlanId(planId); request.setPlanDate(LocalDate.of(2026, 10, 16));
            request.setStatus(1); request.setClientId(key);
            service.reportExecution(211L, 1L, request);
        } else {
            var request = new com.planlock.record.dto.UnlockRecordRequest();
            request.setPlanId(planId); request.setUnlockAt(java.time.LocalDateTime.of(2026, 10, 16, 9, 0));
            request.setClientId(key);
            service.reportUnlock(211L, 1L, request);
        }
    }

    @Test void behaviorHistoryIsOwnerScopedPagedAndDoesNotHoldLegacyClientsOpen() throws Exception {
        long mine = request(215, "POST", "/plans", plan("")).path("id").asLong();
        long other = request(216, "POST", "/plans", plan("")).path("id").asLong();
        for (int i = 0; i < 3; i++) {
            request(215, "POST", "/records/violation", "{\"planId\":" + mine + ",\"recordAt\":\"2026-10-04T09:00:00\",\"startedAt\":\"2026-10-04T09:00:00\",\"endedAt\":\"2026-10-04T09:01:00\",\"durationMs\":60000,\"clientId\":\"rhythm-mine-" + i + "\"}");
            request(216, "POST", "/records/violation", "{\"planId\":" + other + ",\"recordAt\":\"2026-10-04T09:00:00\",\"clientId\":\"rhythm-other-" + i + "\"}");
        }
        var first = request(215, "GET", "/records/history?limit=2&behaviorAfterId=0", null);
        assertThat(first.path("behaviors")).hasSize(2);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        first.path("behaviors").forEach(row -> assertThat(row.path("planId").asLong()).isEqualTo(mine));
        long cursor = first.path("behaviorCursor").asLong();
        var next = request(215, "GET", "/records/history?limit=2&behaviorAfterId=" + cursor, null);
        assertThat(next.path("behaviors")).hasSize(1);
        assertThat(next.path("behaviorCursor").asLong()).isGreaterThan(cursor);
        var legacy = request(215, "GET", "/records/history?limit=2", null);
        assertThat(legacy.path("behaviors")).isEmpty();
        var rhythm = request(216, "GET", "/stats/rhythm?days=30&tz=Asia%2FSingapore", null);
        assertThat(rhythm.path("days")).hasSize(30);
        assertThat(rhythm.path("source").asText()).isEqualTo("Synced");
    }

    @org.apache.ibatis.plugin.Intercepts(@org.apache.ibatis.plugin.Signature(
            type = org.apache.ibatis.executor.Executor.class, method = "update",
            args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class}))
    static final class UploadCommitBarrier implements org.apache.ibatis.plugin.Interceptor {
        final String kind;
        final java.util.concurrent.CountDownLatch firstInserted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch secondStarted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch allowFirstCommit = new java.util.concurrent.CountDownLatch(1);
        volatile boolean active = true;
        UploadCommitBarrier(String kind) { this.kind = kind; }
        @Override public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            Object result = invocation.proceed();
            String mapper = kind.equals("execution") ? "ExecutionRecordMapper.insert" : "UnlockRecordMapper.insert";
            String id = ((org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0]).getId();
            if (active && id.endsWith(mapper) && Thread.currentThread().getName().equals("f101-first-" + kind)) {
                firstInserted.countDown();
                if (!allowFirstCommit.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("First upload barrier timed out");
            }
            return result;
        }
    }

    @Test void historyPagesEachTableByIdAndNeverReturnsAnotherOwnersRows() throws Exception {
        long mine = request(203, "POST", "/plans", plan("")).path("id").asLong();
        long other = request(204, "POST", "/plans", plan("")).path("id").asLong();
        for (int i = 0; i < 3; i++) {
            sql("INSERT INTO execution_records(plan_id,device_id,plan_date,status,client_id) VALUES (" + mine + ",1,'2026-10-16',1," + (i == 0 ? "NULL" : "'history-" + i + "'") + ")");
            sql("INSERT INTO execution_records(plan_id,device_id,plan_date,status) VALUES (" + other + ",1,'2026-10-16',1)");
            sql("INSERT INTO unlock_records(plan_id,device_id,unlock_at,method,client_id) VALUES (" + mine + ",1,'2026-10-16 09:00:00','backup_password'," + (i == 0 ? "NULL" : "'unlock-" + i + "'") + ")");
        }
        var first = request(203, "GET", "/records/history?limit=2", null);
        assertThat(first.path("executions")).hasSize(2);
        assertThat(first.path("unlocks")).hasSize(2);
        assertThat(first.path("executions").get(0).path("clientId").isNull()).isTrue();
        assertThat(first.path("unlocks").get(0).path("clientId").isNull()).isTrue();
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        long ec = first.path("executionCursor").asLong(), uc = first.path("unlockCursor").asLong();
        assertThat(ec).isEqualTo(first.path("executions").get(1).path("id").asLong());
        var second = request(203, "GET", "/records/history?executionAfterId=" + ec + "&unlockAfterId=" + uc + "&limit=2", null);
        assertThat(second.path("executions")).hasSize(1);
        assertThat(second.path("executions").get(0).path("planId").asLong()).isEqualTo(mine);
        assertThat(second.path("executions").get(0).path("id").asLong()).isGreaterThan(ec);
        assertThat(second.path("unlocks")).hasSize(1);
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        var empty = request(205, "GET", "/records/history?executionAfterId=99&unlockAfterId=88", null);
        assertThat(empty.path("executionCursor").asLong()).isEqualTo(99);
        assertThat(empty.path("unlockCursor").asLong()).isEqualTo(88);
        assertThat(empty.path("executions")).isEmpty();
        assertThat(empty.path("unlocks")).isEmpty();
        assertThat(request(203, "GET", "/records/history?limit=0", null).path("executions")).hasSize(1);
        sql("INSERT INTO execution_records(plan_id,device_id,plan_date,status) WITH RECURSIVE n AS (SELECT 1 i UNION ALL SELECT i+1 FROM n WHERE i<1000) SELECT " + mine + ",1,'2026-10-16',1 FROM n");
        var capped = request(203, "GET", "/records/history?limit=2147483647", null);
        assertThat(capped.path("executions")).hasSize(1000);
        assertThat(capped.path("hasMore").asBoolean()).isTrue();
    }

    @Test void uploadReplayDoesNotDuplicateHistoryAndLegacyNullKeysStayVisible() throws Exception {
        long id = request(206, "POST", "/plans", plan("")).path("id").asLong();
        String execution = "{\"planId\":" + id + ",\"planDate\":\"2026-10-16\",\"status\":2,\"clientId\":\"replay-execution\"}";
        String unlock = "{\"planId\":" + id + ",\"unlockAt\":\"2026-10-16T09:00:00\",\"clientId\":\"replay-unlock\"}";
        for (int i = 0; i < 2; i++) { request(206, "POST", "/records/execution", execution); request(206, "POST", "/records/unlock", unlock); }
        var history = request(206, "GET", "/records/history", null);
        assertThat(history.path("executions")).hasSize(1);
        assertThat(history.path("unlocks")).hasSize(1);
    }

    @Test void sharedGoldenFixtureMatchesRealSqlForDayWeekAndMonth() throws Exception {
        JsonNode fixture = json.readTree(getClass().getResourceAsStream("/stats-golden.json"));
        long first = request(207, "POST", "/plans", plan("")).path("id").asLong();
        long second = request(207, "POST", "/plans", plan("")).path("id").asLong();
        for (var item : fixture.path("executions")) {
            sql("INSERT INTO execution_records(plan_id,device_id,plan_date,status,started_at,ended_at,client_id) VALUES ("
                    + (item.path("planId").asInt() == 1 ? first : second) + "," + item.path("deviceId").asInt() + ",'" + item.path("planDate").asText() + "',"
                    + item.path("status").asInt() + ",'" + item.path("startedAt").asText() + "'," + (item.path("endedAt").isNull() ? "NULL" : "'" + item.path("endedAt").asText() + "'") + ","
                    + (item.path("clientId").isNull() ? "NULL" : "'" + item.path("clientId").asText() + "'") + ")");
        }
        for (var item : fixture.path("unlocks")) {
            sql("INSERT INTO unlock_records(plan_id,device_id,unlock_at,method) VALUES (" + second + ",1,'" + item.path("unlockAt").asText() + "','backup_password')");
        }
        LocalDate today = LocalDate.parse(fixture.path("today").asText());
        try (var dates = mockStatic(LocalDate.class, CALLS_REAL_METHODS)) {
            dates.when(LocalDate::now).thenReturn(today);
            for (String period : new String[]{"day", "week", "month"}) {
                var result = json.valueToTree(app.getBean(RecordService.class).stats(207L, period));
                result.path("planExecution").forEach(item -> ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("planId", item.path("planId").asLong() == first ? 1 : 2));
                result.path("earlyEndings").forEach(item -> ((com.fasterxml.jackson.databind.node.ObjectNode) item).put("planId", 2));
                result = json.readTree(json.writeValueAsString(result));
                for (var fields = fixture.path("expected").path(period).fields(); fields.hasNext();) {
                    var entry = fields.next();
                    assertThat(result.path(entry.getKey())).as(period + ": " + entry.getKey()).isEqualTo(entry.getValue());
                }
            }
        }
    }
}

