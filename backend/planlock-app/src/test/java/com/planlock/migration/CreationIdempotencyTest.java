package com.planlock.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.PlanLockApplication;
import com.planlock.common.auth.JwtUtil;
import com.planlock.plan.dto.PlanUpsertRequest;
import com.planlock.plan.service.PlanService;
import com.planlock.schedule.dto.ScheduleUpsertRequest;
import com.planlock.schedule.service.ScheduleService;
import com.planlock.sync.dto.SyncPushRequest;
import com.planlock.sync.service.SyncService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** F80: every write targets this fixture's exact UUID schema, never planlock. */
@EnabledIfEnvironmentVariable(named="F37_ENABLED", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreationIdempotencyTest {
    private final String schema="planlock_f80_"+UUID.randomUUID().toString().replace("-", "");
    private final Path evidence=Path.of(System.getenv().getOrDefault("F37_EVIDENCE_DIR", "target/f80-evidence"));
    private ConfigurableApplicationContext app;
    private boolean owned;
    private ObjectMapper json;
    private PlanService plans;
    private ScheduleService schedules;
    private SyncService sync;
    private String url(){return "jdbc:mysql://localhost:3306/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";}
    private Connection connection(String url) throws Exception {return DriverManager.getConnection(url,System.getenv("F37_DB_USER"),System.getenv("F37_DB_PASSWORD"));}
    private long count(String table,long user) throws Exception {try(var c=connection(url());var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table+" WHERE user_id="+user)){r.next();return r.getLong(1);}}
    private void record(String file,String value) throws Exception {Files.createDirectories(evidence);Files.writeString(evidence.resolve(file),value,StandardCharsets.UTF_8);}
    @BeforeAll void setup() throws Exception {
        if(!schema.matches("planlock_f80_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe schema");
        try(var c=connection("jdbc:mysql://localhost:3306/?useSSL=false&allowPublicKeyRetrieval=true");var s=c.createStatement()) {s.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");owned=true;}
        try {
            record(schema+"-ownership.txt","Created only "+schema+"\n");
            app=new SpringApplication(PlanLockApplication.class).run("--spring.profiles.active=f37","--server.port=0","--spring.datasource.url="+url(),"--spring.datasource.username="+System.getenv("F37_DB_USER"),"--spring.datasource.password="+System.getenv("F37_DB_PASSWORD"));
            json=app.getBean(ObjectMapper.class);plans=app.getBean(PlanService.class);schedules=app.getBean(ScheduleService.class);sync=app.getBean(SyncService.class);
        } catch(Exception|Error failure){cleanup();throw failure;}
    }
    @AfterAll void cleanup() throws Exception {
        if(app!=null){app.close();app=null;}
        if(owned){try(var c=connection("jdbc:mysql://localhost:3306/?useSSL=false&allowPublicKeyRetrieval=true");var s=c.createStatement()){s.execute("DROP DATABASE "+schema);owned=false;}record(schema+"-cleanup.txt","Dropped only owned schema "+schema+"\n");}
    }
    private String planJson(String key){return "{\"name\":\"offline plan\",\"type\":\"学习\",\"startTime\":\"08:00:00\",\"endTime\":\"09:00:00\""+(key==null?"":",\"clientId\":\""+key+"\"")+"}";}
    private String scheduleJson(String key){return "{\"title\":\"offline schedule\",\"startDate\":\"2026-10-09\",\"endDate\":\"2026-10-09\",\"allDay\":true"+(key==null?"":",\"clientId\":\""+key+"\"")+"}";}
    private PlanUpsertRequest plan(String key) throws Exception {return json.readValue(planJson(key),PlanUpsertRequest.class);}
    private ScheduleUpsertRequest schedule(String key) throws Exception {return json.readValue(scheduleJson(key),ScheduleUpsertRequest.class);}
    @Test void crudReplayIsUserScopedAndDoesNotOverwriteNewerEdits() throws Exception {
        var p=plans.create(101L,plan("crud-plan"));assertThat(plans.create(101L,plan("crud-plan")).getId()).isEqualTo(p.getId());
        assertThat(plans.create(102L,plan("crud-plan")).getId()).isNotEqualTo(p.getId());
        var s=schedules.create(101L,schedule("crud-schedule"));assertThat(schedules.create(101L,schedule("crud-schedule")).getId()).isEqualTo(s.getId());
        assertThat(schedules.create(102L,schedule("crud-schedule")).getId()).isNotEqualTo(s.getId());
        var edit=plan("crud-plan");edit.setName("edited");plans.update(101L,p.getId(),edit,0L);
        var replay=plans.create(101L,plan("crud-plan"));assertThat(replay.getName()).isEqualTo("edited");assertThat(replay.getVersion()).isEqualTo(1L);
        assertThat(count("plans",101)).isEqualTo(1);assertThat(count("schedules",101)).isEqualTo(1);
    }
    @Test void legacyNullKeysRemainIndependent() throws Exception {
        assertThat(plans.create(103L,plan(null)).getId()).isNotEqualTo(plans.create(103L,plan(null)).getId());
        assertThat(schedules.create(103L,schedule(null)).getId()).isNotEqualTo(schedules.create(103L,schedule(null)).getId());
    }
    @Test
    void concurrentCrudAndSyncCreatesReturnOneIdentity() throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var gate = new CountDownLatch(1);
            List<Future<Long>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                final boolean crud = i % 2 == 0;
                results.add(pool.submit(() -> {
                    gate.await();
                    if (crud) {
                        return plans.create(104L, plan("race-plan")).getId();
                    }
                    var request = json.readValue(
                            "{\"plans\":[" + planJson("race-plan") + "]}", SyncPushRequest.class);
                    var result = json.valueToTree(sync.push(104L, request)).path("results").get(0);
                    assertThat(result.path("ok").asBoolean()).isTrue();
                    assertThat(result.path("id").asLong()).isPositive();
                    return result.path("id").asLong();
                }));
            }
            gate.countDown();

            Set<Long> ids = new HashSet<>();
            for (var result : results) {
                ids.add(result.get(30, TimeUnit.SECONDS));
            }
            assertThat(ids).hasSize(1);
            assertThat(count("plans", 104)).isEqualTo(1);
        }
    }

    @Test void syncReplayReturnsMappingAndPreservesLegacyKey() throws Exception {
        String body="{\"plans\":["+planJson("sync-plan")+"],\"schedules\":["+scheduleJson("sync-schedule")+"]}";
        var first=json.valueToTree(sync.push(105L,json.readValue(body,SyncPushRequest.class))).path("results");
        var retry=json.valueToTree(sync.push(105L,json.readValue(body,SyncPushRequest.class))).path("results");
        for(int i=0;i<2;i++){assertThat(first.get(i).path("id").asLong()).isPositive();assertThat(retry.get(i).path("id")).isEqualTo(first.get(i).path("id"));assertThat(first.get(i).path("version").asLong(-1)).isZero();}
        assertThat(first.get(0).path("key").asText()).isEqualTo("plan:null");assertThat(first.get(0).path("clientId").asText()).isEqualTo("sync-plan");
    }
    @Test void deleteRetryConfirmsAbsentResourceAndCannotDeleteOtherUsersResource() throws Exception {
        var p=plans.create(106L,plan("delete-plan"));plans.delete(106L,p.getId());assertThatCode(()->plans.delete(106L,p.getId())).doesNotThrowAnyException();
        var s=schedules.create(106L,schedule("delete-schedule"));schedules.delete(106L,s.getId());assertThatCode(()->schedules.delete(106L,s.getId())).doesNotThrowAnyException();
        var other=plans.create(107L,plan("other"));assertThatThrownBy(()->plans.delete(106L,other.getId())).isInstanceOf(com.planlock.common.BusinessException.class);
    }
    @Test
    void committedResponseDiscardedBeforeReplayCreatesExactlyOneRow() throws Exception {
        int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
        String token = app.getBean(JwtUtil.class).generate(108L, 1L);
        String body = scheduleJson("disconnect-schedule");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "POST /api/v1/schedules HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Authorization: Bearer " + token + "\r\n"
                + "Content-Type: application/json\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        try (var socket = new Socket("127.0.0.1", port)) {
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(bytes);
            socket.getOutputStream().flush();

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (count("schedules", 108) == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(count("schedules", 108)).isEqualTo(1);
            // Commit observed; response deliberately unread, connection reset.
            socket.setSoLinger(true, 0);
        }

        var request = HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/schedules"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.readTree(response.body()).path("data").path("id").asLong()).isPositive();
        assertThat(count("schedules", 108)).isEqualTo(1);
        record("f80-disconnected-response.txt",
                "First HTTP creation committed; response unread and TCP reset. "
                        + "Same clientId replay HTTP 200; user 108 schedule rows=1.\n");
    }

    @Test
    void opaqueKeysWithTrailingSpaceRemainDistinctAcrossCrudAndSync() throws Exception {
        var planA = plans.create(109L, plan("a"));
        String planBody = "{\"plans\":[" + planJson("a ") + "]}";
        var planB = json.valueToTree(sync.push(109L, json.readValue(planBody, SyncPushRequest.class)))
                .path("results").get(0);
        assertThat(planB.path("ok").asBoolean()).isTrue();
        assertThat(planB.path("clientId").asText()).isEqualTo("a ");
        assertThat(planB.path("id").asLong()).isNotEqualTo(planA.getId());
        assertThat(plans.create(109L, plan("a ")).getId()).isEqualTo(planB.path("id").asLong());

        String scheduleBody = "{\"schedules\":[" + scheduleJson("a") + "]}";
        var scheduleA = json.valueToTree(sync.push(110L, json.readValue(scheduleBody, SyncPushRequest.class)))
                .path("results").get(0);
        var scheduleB = schedules.create(110L, schedule("a "));
        assertThat(scheduleB.getClientId()).isEqualTo("a ");
        assertThat(scheduleB.getId()).isNotEqualTo(scheduleA.path("id").asLong());
        String scheduleReplay = "{\"schedules\":[" + scheduleJson("a ") + "]}";
        var replay = json.valueToTree(sync.push(110L, json.readValue(scheduleReplay, SyncPushRequest.class)))
                .path("results").get(0);
        assertThat(replay.path("id").asLong()).isEqualTo(scheduleB.getId());
        assertThat(replay.path("clientId").asText()).isEqualTo("a ");
        assertThat(count("plans", 109)).isEqualTo(2);
        assertThat(count("schedules", 110)).isEqualTo(2);
    }

    @Test
    void strategyIdentityDoesNotReversePlanThenStrategyLockOrder() throws Exception {
        var plan = plans.create(111L, plan("lock-order"));
        var initial = new SyncPushRequest.StrategyItem();
        initial.setPlanId(plan.getId());
        initial.setAutoDnd(false);
        initial.setNotifySilence(false);
        var seed = new SyncPushRequest();
        seed.setStrategies(List.of(initial));
        assertThat(sync.push(111L, seed).getResults().getFirst().isOk()).isTrue();

        var strategyWritten = new CountDownLatch(1);
        var mixedStrategyAttempted = new CountDownLatch(1);
        var barrier = new StrategyWriteBarrier(strategyWritten, mixedStrategyAttempted);
        app.getBean(org.apache.ibatis.session.SqlSessionFactory.class).getConfiguration().addInterceptor(barrier);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var strategyOnly = pool.submit(() -> {
                Thread.currentThread().setName("f80-strategy-only");
                var request = new SyncPushRequest();
                var item = new SyncPushRequest.StrategyItem();
                item.setPlanId(plan.getId());
                item.setBaseVersion(0L);
                item.setAutoDnd(true);
                item.setNotifySilence(false);
                request.setStrategies(List.of(item));
                return sync.push(111L, request);
            });
            assertThat(strategyWritten.await(10, TimeUnit.SECONDS)).isTrue();
            var mixed = pool.submit(() -> {
                Thread.currentThread().setName("f80-plan-and-strategy");
                var request = json.readValue("{\"plans\":[" + planJson("lock-order") + "]}",
                        SyncPushRequest.class);
                request.getPlans().getFirst().setId(plan.getId());
                request.getPlans().getFirst().setBaseVersion(0L);
                var item = new SyncPushRequest.StrategyItem();
                item.setPlanId(plan.getId());
                item.setBaseVersion(1L); // A commits 0 -> 1; B then applies 1 -> 2.
                item.setAutoDnd(true);
                item.setNotifySilence(true);
                request.setStrategies(List.of(item));
                return sync.push(111L, request);
            });

            var a = strategyOnly.get(20, TimeUnit.SECONDS).getResults().getFirst();
            var b = mixed.get(20, TimeUnit.SECONDS).getResults();
            assertThat(a.isOk()).isTrue();
            assertThat(a.getId()).isPositive();
            assertThat(a.getVersion()).isEqualTo(1L);
            assertThat(b).hasSize(2).allMatch(com.planlock.sync.dto.SyncItemResult::isOk);
            assertThat(b.get(0).getId()).isEqualTo(plan.getId());
            assertThat(b.get(0).getVersion()).isEqualTo(1L);
            assertThat(b.get(1).getId()).isEqualTo(a.getId());
            assertThat(b.get(1).getVersion()).isEqualTo(2L);
            record("f80-lock-order.txt",
                    "Barrier A: strategy write acquired; B: plan written and strategy update attempted; "
                            + "A identity read completes without parent-plan lock. Both commits succeed; "
                            + "plan version=1; strategy versions returned A=1, B=2.\n");
        } finally {
            barrier.active = false;
        }
    }

    @org.apache.ibatis.plugin.Intercepts({
            @org.apache.ibatis.plugin.Signature(
                    type = org.apache.ibatis.executor.Executor.class,
                    method = "update",
                    args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class})
    })
    static final class StrategyWriteBarrier implements org.apache.ibatis.plugin.Interceptor {
        private final CountDownLatch strategyWritten;
        private final CountDownLatch mixedStrategyAttempted;
        volatile boolean active = true;

        StrategyWriteBarrier(CountDownLatch strategyWritten, CountDownLatch mixedStrategyAttempted) {
            this.strategyWritten = strategyWritten;
            this.mixedStrategyAttempted = mixedStrategyAttempted;
        }

        @Override
        public Object intercept(org.apache.ibatis.plugin.Invocation invocation) throws Throwable {
            var statement = (org.apache.ibatis.mapping.MappedStatement) invocation.getArgs()[0];
            if (!active || !statement.getId().endsWith("SyncMapper.updateStrategy")) {
                return invocation.proceed();
            }
            String thread = Thread.currentThread().getName();
            if (thread.equals("f80-plan-and-strategy")) {
                // B already holds its plan row lock; its next SQL waits for A's strategy lock.
                mixedStrategyAttempted.countDown();
                return invocation.proceed();
            }
            Object result = invocation.proceed();
            if (thread.equals("f80-strategy-only")) {
                strategyWritten.countDown();
                if (!mixedStrategyAttempted.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Mixed transaction did not attempt strategy write");
                }
            }
            return result;
        }
    }

}
