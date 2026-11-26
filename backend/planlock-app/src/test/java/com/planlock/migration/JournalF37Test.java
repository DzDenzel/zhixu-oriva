package com.planlock.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.PlanLockApplication;
import com.planlock.account.mapper.MergeMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.common.auth.JwtUtil;
import com.planlock.record.journal.dto.JournalUpsertRequest;
import com.planlock.record.journal.service.JournalService;
import com.planlock.sync.dto.SyncPushRequest;
import com.planlock.sync.service.SyncService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real MySQL F37 verification for REQ-039 journal REST, sync, ownership, and guest merge. */
@EnabledIfEnvironmentVariable(named = "F37_ENABLED", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JournalF37Test {
    private final String schema = "planlock_f37_journal_" + UUID.randomUUID().toString().replace("-", "");
    private ConfigurableApplicationContext app;
    private ObjectMapper json;
    private JournalService journals;
    private SyncService sync;
    private MergeMapper mergeMapper;
    private boolean owned;

    private Connection connection(String database) throws Exception {
        return DriverManager.getConnection(TestDatabase.url(database),
                System.getenv("F37_DB_USER"), System.getenv("F37_DB_PASSWORD"));
    }

    @BeforeAll
    void setup() throws Exception {
        if (!schema.matches("planlock_f37_journal_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe schema");
        try (var c = connection(""); var s = c.createStatement()) {
            s.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            owned = true;
        }
        try {
            app = new SpringApplication(PlanLockApplication.class).run(
                    "--spring.profiles.active=f37", "--server.port=0",
                    "--spring.datasource.url=" + TestDatabase.url(schema),
                    "--spring.datasource.username=" + System.getenv("F37_DB_USER"),
                    "--spring.datasource.password=" + System.getenv("F37_DB_PASSWORD"));
            json = app.getBean(ObjectMapper.class);
            journals = app.getBean(JournalService.class);
            sync = app.getBean(SyncService.class);
            mergeMapper = app.getBean(MergeMapper.class);
            try (var c = connection(schema); var s = c.createStatement()) {
                s.execute("INSERT INTO users(id) VALUES (501),(502),(503)");
            }
        } catch (Exception | Error failure) {
            cleanup();
            throw failure;
        }
    }

    @AfterAll
    void cleanup() throws Exception {
        if (app != null) { app.close(); app = null; }
        if (owned) {
            try (var c = connection(""); var s = c.createStatement()) { s.execute("DROP DATABASE " + schema); }
            owned = false;
        }
    }

    @Test
    void restOwnershipDeleteUniformityAndGuestMergeWithClientIdDeduplication() throws Exception {
        var req = new JournalUpsertRequest();
        req.setClientId("f37-journal-1");
        req.setEntryDate(LocalDate.of(2026, 11, 26));
        req.setMood("平静");
        req.setTags(List.of("测试", "测试"));
        req.setContent("F37 第一条日记");

        var created = journals.create(501L, req);
        assertThat(journals.create(501L, req).getId()).isEqualTo(created.getId());

        // Foreign delete and missing delete must both fail with NOT_FOUND (1003)
        int port = ((ServletWebServerApplicationContext) app).getWebServer().getPort();
        String foreignJwt = app.getBean(JwtUtil.class).generate(502L, 1L);
        var client = HttpClient.newHttpClient();
        var deleteForeign = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/journals/" + created.getId()))
                        .header("Authorization", "Bearer " + foreignJwt).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        var deleteMissing = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/journals/999999"))
                        .header("Authorization", "Bearer " + foreignJwt).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(json.readTree(deleteForeign.body()).path("code").asInt()).isEqualTo(ErrorCode.NOT_FOUND.getCode());
        assertThat(json.readTree(deleteMissing.body()).path("code").asInt()).isEqualTo(ErrorCode.NOT_FOUND.getCode());

        // Scenario B: exact equivalent clientId collides -> guest duplicate cleaned cleanly
        var dup = new JournalUpsertRequest();
        dup.setClientId("shared-cid");
        dup.setEntryDate(LocalDate.of(2026, 11, 26));
        dup.setMood("平静");
        dup.setTags(List.of("测试"));
        dup.setContent("游客重复条目");
        journals.create(501L, dup);
        journals.create(503L, dup);

        // Scenario A1: same clientId, divergent content -> lossless rekey + transfer
        var guestDivergent = new JournalUpsertRequest();
        guestDivergent.setClientId("conflict-content-cid");
        guestDivergent.setEntryDate(LocalDate.of(2026, 11, 26));
        guestDivergent.setContent("游客版本正文");
        journals.create(501L, guestDivergent);
        var ownerDivergent = new JournalUpsertRequest();
        ownerDivergent.setClientId("conflict-content-cid");
        ownerDivergent.setEntryDate(LocalDate.of(2026, 11, 26));
        ownerDivergent.setContent("账号版本正文");
        journals.create(503L, ownerDivergent);

        // Scenario A2: same clientId, target soft-deleted (status=0) vs guest active (status=1) -> lossless rekey + preserve active guest row
        var guestActive = new JournalUpsertRequest();
        guestActive.setClientId("conflict-status-cid");
        guestActive.setEntryDate(LocalDate.of(2026, 11, 26));
        guestActive.setContent("同一clientId内容");
        journals.create(501L, guestActive);
        var targetToDelete = new JournalUpsertRequest();
        targetToDelete.setClientId("conflict-status-cid");
        targetToDelete.setEntryDate(LocalDate.of(2026, 11, 26));
        targetToDelete.setContent("同一clientId内容");
        var targetCreated = journals.create(503L, targetToDelete);
        journals.delete(503L, targetCreated.getId(), targetCreated.getVersion());

        int rekeyed = mergeMapper.rekeyConflictingGuestJournals(501L, 503L);
        int transferred = mergeMapper.transferJournals(501L, 503L);
        mergeMapper.clearDuplicateGuestJournals(501L, 503L);
        assertThat(rekeyed).isEqualTo(2);
        assertThat(transferred).isEqualTo(3);
        assertThat(journals.list(501L, null, null, null)).isEmpty();
        var ownerJournals = journals.list(503L, null, null, null);
        assertThat(ownerJournals).extracting("content")
                .contains("F37 第一条日记", "游客重复条目", "游客版本正文", "账号版本正文", "同一clientId内容");

        // Idempotent repeat merge returns 0
        assertThat(mergeMapper.rekeyConflictingGuestJournals(501L, 503L)).isZero();
        assertThat(mergeMapper.transferJournals(501L, 503L)).isZero();
    }

    @Test
    void exportHeaderContainsNoStoreAndSyncHandlesNullElements() {
        var push = new SyncPushRequest();
        var item = new SyncPushRequest.JournalItem();
        item.setClientId("f37-sync-1");
        item.setEntryDate(LocalDate.of(2026, 11, 26));
        item.setContent("同步测试");
        push.setJournals(java.util.Arrays.asList(null, item));
        var results = sync.push(502L, push).getResults();
        assertThat(results).hasSize(2);
        assertThat(results.get(0).isOk()).isFalse();
        assertThat(results.get(1).isOk()).isTrue();
        assertThatThrownBy(() -> journals.delete(501L, results.get(1).getId(), null))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }
}
