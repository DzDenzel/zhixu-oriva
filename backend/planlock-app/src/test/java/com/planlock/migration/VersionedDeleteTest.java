package com.planlock.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.PlanLockApplication;
import com.planlock.common.auth.JwtUtil;
import com.planlock.plan.dto.PlanUpsertRequest;
import com.planlock.plan.service.PlanService;
import com.planlock.schedule.dto.ScheduleUpsertRequest;
import com.planlock.schedule.service.ScheduleService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/** Real HTTP/MySQL regression for BUG-096, using an owned F37 schema only. */
@EnabledIfEnvironmentVariable(named = "F37_ENABLED", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VersionedDeleteTest {
    private final String schema = "planlock_f37_delete_" + UUID.randomUUID().toString().replace("-", "");
    private ConfigurableApplicationContext app;
    private ObjectMapper json;
    private boolean owned;

    private Connection connection(String database) throws SQLException {
        return DriverManager.getConnection(TestDatabase.url(database),
                System.getenv("F37_DB_USER"), System.getenv("F37_DB_PASSWORD"));
    }

    @BeforeAll void setup() throws Exception {
        if (!schema.matches("planlock_f37_delete_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe schema");
        try (var c = connection(""); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            owned = true;
        }
        try {
            app = new SpringApplication(PlanLockApplication.class).run("--spring.profiles.active=f37",
                    "--server.port=0", "--spring.datasource.url=" + TestDatabase.url(schema),
                    "--spring.datasource.username=" + System.getenv("F37_DB_USER"),
                    "--spring.datasource.password=" + System.getenv("F37_DB_PASSWORD"));
            json = app.getBean(ObjectMapper.class);
        } catch (Exception | Error failure) { cleanup(); throw failure; }
    }

    @AfterAll void cleanup() throws Exception {
        if (app != null) { app.close(); app = null; }
        if (owned) {
            try (var c = connection(""); var s = c.createStatement()) { s.execute("DROP DATABASE " + schema); }
            owned = false;
        }
    }

    private long create(String table) throws Exception {
        if (table.equals("plans")) {
            var request = json.readValue("{\"name\":\"delete fixture\",\"type\":\"学习\","
                    + "\"startTime\":\"08:00:00\",\"endTime\":\"09:00:00\"}", PlanUpsertRequest.class);
            return app.getBean(PlanService.class).create(201L, request).getId();
        }
        var request = json.readValue("{\"title\":\"delete fixture\",\"startDate\":\"2026-10-13\","
                + "\"endDate\":\"2026-10-13\",\"allDay\":true}", ScheduleUpsertRequest.class);
        return app.getBean(ScheduleService.class).create(201L, request).getId();
    }

    private void execute(String sql) throws Exception {
        try (var c = connection(schema); var s = c.createStatement()) { s.executeUpdate(sql); }
    }

    private Long version(String table, long id) throws Exception {
        try (var c = connection(schema); var s = c.createStatement();
             var r = s.executeQuery("SELECT version FROM " + table + " WHERE id=" + id)) {
            return r.next() ? r.getLong(1) : null;
        }
    }

    private int delete(String table, long id, long user, Long baseVersion) throws Exception {
        int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/"
                        + table + "/" + id + (baseVersion == null ? "" : "?baseVersion=" + baseVersion)))
                .header("Authorization", "Bearer " + app.getBean(JwtUtil.class).generate(user, 1L))
                .DELETE().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("code").asInt();
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void planDeletionAlsoDeletesStrategyAndDetachesSchedule(boolean versioned) throws Exception {
        long id=create("plans"); long scheduleId=create("schedules");
        execute("INSERT INTO plan_strategies(plan_id,auto_dnd,notify_silence,version) VALUES ("+id+",1,1,0)");
        execute("UPDATE schedules SET linked_plan_id="+id+" WHERE id="+scheduleId);
        assertThat(delete("plans",id,201L,versioned ? 0L : null)).isZero();
        assertThat(strategyCount(id)).isZero();
        try(var c=connection(schema);var st=c.createStatement();var r=st.executeQuery("SELECT linked_plan_id FROM schedules WHERE id="+scheduleId)) {
            assertThat(r.next()).isTrue(); assertThat(r.getObject(1)).isNull();
        }
    }
    @Test void stalePlanDeletionKeepsAssociatedStrategy() throws Exception {
        long id=create("plans");
        execute("INSERT INTO plan_strategies(plan_id,auto_dnd,notify_silence,version) VALUES ("+id+",1,1,0)");
        execute("UPDATE plans SET version=1 WHERE id="+id);
        assertThat(delete("plans",id,201L,0L)).isEqualTo(1409);
        assertThat(strategyCount(id)).isEqualTo(1L);
    }
    @Test void foreignPlanDeletionCannotDeleteAssociatedStrategy() throws Exception {
        long id=create("plans");
        execute("INSERT INTO plan_strategies(plan_id,auto_dnd,notify_silence,version) VALUES ("+id+",1,1,0)");
        assertThat(delete("plans",id,202L,0L)).isEqualTo(1003);
        assertThat(strategyCount(id)).isEqualTo(1L);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void strategyCannotBeInsertedAfterItsParentWasDeleted(boolean sync) throws Exception {
        long id=create("plans");
        var barrier=new BeforeStrategyInsert(sync ? "SyncMapper.insertStrategy" : "PlanStrategyMapper.insert", () -> execute("DELETE FROM plans WHERE id="+id));
        app.getBean(SqlSessionFactory.class).getConfiguration().addInterceptor(barrier);
        try {
            int port=((ServletWebServerApplicationContext)app).getWebServer().getPort();
            String body=sync ? "{\"strategies\":[{\"planId\":"+id+",\"autoDnd\":true,\"notifySilence\":false}]}" : "{\"autoDnd\":true,\"notifySilence\":false}";
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/"+(sync ? "sync/push" : "plans/"+id+"/strategy")))
                    .header("Authorization","Bearer "+app.getBean(JwtUtil.class).generate(201L,1L))
                    .header("Content-Type","application/json").method(sync ? "POST" : "PUT",HttpRequest.BodyPublishers.ofString(body)).build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var parsed=json.readTree(response.body());
            assertThat(sync ? parsed.path("data").path("results").get(0).path("code").asInt() : parsed.path("code").asInt()).isEqualTo(1003);
            assertThat(barrier.fired.get()).isTrue(); assertThat(strategyCount(id)).isZero();
        } finally { barrier.active=false; }
    }
    @Intercepts(@Signature(type=Executor.class,method="update",args={MappedStatement.class,Object.class}))
    static final class BeforeStrategyInsert implements Interceptor {
        final String method; final SqlAction action; final AtomicBoolean fired=new AtomicBoolean(); volatile boolean active=true;
        BeforeStrategyInsert(String method,SqlAction action) { this.method=method; this.action=action; }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var statement=(MappedStatement)invocation.getArgs()[0];
            if(active && statement.getId().endsWith(method) && fired.compareAndSet(false,true)) action.run();
            return invocation.proceed();
        }
    }

    private long strategyCount(long planId) throws Exception {
        try(var c=connection(schema);var st=c.createStatement();var r=st.executeQuery("SELECT COUNT(*) FROM plan_strategies WHERE plan_id="+planId)) {
            r.next(); return r.getLong(1);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"plans", "schedules"})
    void staleVersionCannotDeleteNewerRemoteEdit(String table) throws Exception {
        long id = create(table);
        execute("UPDATE " + table + " SET version=1 WHERE id=" + id);
        assertThat(delete(table, id, 201L, 0L)).isEqualTo(1409);
        assertThat(version(table, id)).isEqualTo(1L);
    }

    @ParameterizedTest @ValueSource(strings = {"plans", "schedules"})
    void matchingVersionDeletesAndAbsentRetryIsIdempotent(String table) throws Exception {
        long id = create(table);
        assertThat(delete(table, id, 201L, 0L)).isZero();
        assertThat(version(table, id)).isNull();
        assertThat(delete(table, id, 201L, 0L)).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"plans", "schedules"})
    void omittedVersionRetainsLegacyDelete(String table) throws Exception {
        long id = create(table);
        execute("UPDATE " + table + " SET version=7 WHERE id=" + id);
        assertThat(delete(table, id, 201L, null)).isZero();
        assertThat(version(table, id)).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"plans", "schedules"})
    void otherUserCannotDeleteEvenWithMatchingVersion(String table) throws Exception {
        long id = create(table);
        assertThat(delete(table, id, 202L, 0L)).isEqualTo(1003);
        assertThat(delete(table, id, 202L, null)).isEqualTo(1003);
        assertThat(version(table, id)).isEqualTo(0L);
    }

    @ParameterizedTest @ValueSource(strings = {"plans", "schedules"})
    void editCommittedBetweenReadAndDeleteCannotBeLost(String table) throws Exception {
        long id = create(table);
        var barrier = new BeforeDelete(table, () -> execute("UPDATE " + table
                + " SET version=version+1 WHERE id=" + id));
        app.getBean(SqlSessionFactory.class).getConfiguration().addInterceptor(barrier);
        try {
            assertThat(delete(table, id, 201L, 0L)).isEqualTo(1409);
            assertThat(barrier.fired.get()).isTrue();
            assertThat(version(table, id)).isEqualTo(1L);
        } finally { barrier.active = false; }
    }

    @ParameterizedTest @ValueSource(strings = {"plans", "schedules"})
    void concurrentDeleteAfterInitialReadRemainsIdempotent(String table) throws Exception {
        long id = create(table);
        var barrier = new BeforeDelete(table, () -> execute("DELETE FROM " + table + " WHERE id=" + id));
        app.getBean(SqlSessionFactory.class).getConfiguration().addInterceptor(barrier);
        try {
            assertThat(delete(table, id, 201L, 0L)).isZero();
            assertThat(barrier.fired.get()).isTrue();
            assertThat(version(table, id)).isNull();
        } finally { barrier.active = false; }
    }

    @FunctionalInterface interface SqlAction { void run() throws Exception; }

    /** Intercepts only timing; both DELETE and competing writes execute against real MySQL. */
    @Intercepts(@Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}))
    static final class BeforeDelete implements Interceptor {
        final String mapper;
        final SqlAction action;
        final AtomicBoolean fired = new AtomicBoolean();
        volatile boolean active = true;
        BeforeDelete(String table, SqlAction action) {
            mapper = table.equals("plans") ? "PlanMapper.delete" : "ScheduleMapper.delete";
            this.action = action;
        }
        @Override public Object intercept(Invocation invocation) throws Throwable {
            var statement = (MappedStatement) invocation.getArgs()[0];
            if (active && statement.getId().contains(mapper) && fired.compareAndSet(false, true)) action.run();
            return invocation.proceed();
        }
    }
}
