package com.planlock.migration;

import com.planlock.PlanLockApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

/** F37: opt in explicitly; only UUID schemas created by this test may be mutated. */
@EnabledIfEnvironmentVariable(named = "F37_ENABLED", matches = "true")
class DatabaseMigrationTest {
    private static final String PREFIX = "planlock_f37_";
    private static final Path EVIDENCE = Path.of(System.getenv().getOrDefault("F37_EVIDENCE_DIR", "target/f37-evidence"));

    @Test
    void emptySchemaStartsWithEightTablesAndAllMigrationVersions() throws Exception {
        try (Schema schema = new Schema("empty")) {
            try (var app = start(schema)) {
                assertThat(app.isActive()).isTrue();
                assertBusinessStructure(schema);
                assertThat(history(schema)).containsExactly("1|SQL|1", "2|SQL|1", "3|SQL|1");
                snapshot(schema, "empty");
            }
        }
    }

    @Test
    void existingV2SchemaUpgradesWithoutLosingDataAndRestartIsIdempotent() throws Exception {
        try (Schema schema = new Schema("existing")) {
            // Independent current-state fixture, never executes CREATE DATABASE or USE from schema.sql.
            String reference = new ClassPathResource("db/schema.sql").getContentAsString(StandardCharsets.UTF_8);
            String tablesOnly = reference.substring(reference.indexOf("CREATE TABLE IF NOT EXISTS users"));
            try (var c = schema.connection()) {
                ScriptUtils.executeSqlScript(c, new org.springframework.core.io.ByteArrayResource(tablesOnly.getBytes(StandardCharsets.UTF_8)));
            }
            schema.execute("INSERT INTO users(id,email) VALUES (9001,'f37-sentinel@example.invalid')");
            snapshot(schema, "existing-before");
            List<String> after;
            List<String> dataBefore = rowCounts(schema);
            try (var app = start(schema, "--spring.flyway.baseline-on-migrate=true", "--spring.flyway.baseline-version=2")) {
                assertThat(app.isActive()).isTrue();
                assertBusinessStructure(schema);
                assertThat(history(schema)).containsExactly("2|BASELINE|1", "3|SQL|1");
                after = snapshot(schema, "existing");
                assertCreationIdentityStructure(schema);
                assertThat(rowCounts(schema)).isEqualTo(dataBefore);
            }
            try (var app = start(schema)) {
                assertThat(app.isActive()).isTrue();
                assertThat(history(schema)).containsExactly("2|BASELINE|1", "3|SQL|1");
                assertThat(snapshot(schema, "restart")).isEqualTo(after);
                assertThat(rowCounts(schema)).isEqualTo(dataBefore);
                assertThat(schema.query("SELECT email FROM users WHERE id=9001")).containsExactly("f37-sentinel@example.invalid");
            }
            try (Schema fresh = new Schema("compare")) {
                try (var app = start(fresh)) {
                    assertBusinessStructure(fresh);
                    assertThat(snapshot(fresh, "comparison-empty")).isEqualTo(after);
                    evidence("structure-diff.txt", "fresh V3 == upgraded existing V2 == restart; difference: []\n");
                }
            }
        }
    }

    @Test
    void brokenMigrationStopsApplicationStartupWithFailedHistoryEntry() throws Exception {
        try (Schema schema = new Schema("broken")) {
            assertThatThrownBy(() -> start(schema, "--spring.flyway.locations=classpath:db/migration,classpath:db/f37-broken"))
                    .hasStackTraceContaining("V4__intentional_syntax_error.sql")
                    .hasStackTraceContaining("SQL State");
            assertThat(history(schema)).containsExactly("1|SQL|1", "2|SQL|1", "3|SQL|1", "4|SQL|0");
            assertBusinessStructure(schema);
            snapshot(schema, "broken");
        }
    }

    @Test
    void baselineOverrideOnCompletelyEmptySchemaStillExecutesAllVersions() throws Exception {
        try (Schema schema = new Schema("override_empty")) {
            try (var app = start(schema, "--spring.flyway.baseline-on-migrate=true", "--spring.flyway.baseline-version=2")) {
                assertThat(app.isActive()).isTrue();
                assertBusinessStructure(schema);
                assertThat(history(schema)).containsExactly("1|SQL|1", "2|SQL|1", "3|SQL|1");
                evidence("baseline-empty.txt", "Completely empty schema: V1, V2 and V3 executed despite baseline override.\n");
            }
        }
    }

    @Test
    void baselineOverrideOnNonemptyIncompleteSchemaFailsV3Migration() throws Exception {
        try (Schema schema = new Schema("override_nonempty")) {
            schema.execute("CREATE TABLE unrelated_marker(id INT PRIMARY KEY)");
            assertThatThrownBy(() -> start(schema, "--spring.flyway.baseline-on-migrate=true", "--spring.flyway.baseline-version=2"))
                    .hasStackTraceContaining("V3__creation_idempotency.sql");
            assertThat(schema.query("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + schema.name + "' AND TABLE_NAME='violation_records'")).isEmpty();
            evidence("baseline-nonempty.txt", "Nonempty incomplete schema: baseline 2; V3 refuses missing business tables at startup.\n");
        }
    }


    @Test
    void defaultConfigurationRefusesUnbaselinedNonemptySchema() throws Exception {
        try (Schema schema = new Schema("safe_default")) {
            schema.execute("CREATE TABLE unrelated_marker(id INT PRIMARY KEY)");
            assertThatThrownBy(() -> start(schema)).hasStackTraceContaining("non-empty schema");
        }
    }

    @Test
    void ownershipEvidenceFailureDropsTheCreatedSchema(@TempDir Path directory) throws Exception {
        Path blocked = Files.writeString(directory.resolve("blocked"), "regular file");
        String name = PREFIX + "evidence_failure_" + UUID.randomUUID().toString().replace("-", "");
        AtomicBoolean created = new AtomicBoolean();
        try {
            assertThatThrownBy(() -> new Schema("evidence_failure", name, (filename, text) -> {
                created.set(true); // Writer entry proves CREATE succeeded.
                Files.createDirectories(blocked);
                Files.writeString(blocked.resolve(filename), text, StandardCharsets.UTF_8);
            })).isInstanceOf(IOException.class);
            assertThat(schemaExists(name)).as("failed construction must release its database").isFalse();
        } finally {
            // The RED run leaks this exact schema; this test owns its name and cleans it safely.
            if (created.get()) dropTestSchemaIfPresent(name);
        }
    }

    @Test
    void cleanupFailureIsSuppressedOnTheOriginalEvidenceFailure() throws Exception {
        String name = PREFIX + "cleanup_failure_" + UUID.randomUUID().toString().replace("-", "");
        IOException original = new IOException("injected ownership evidence failure");
        AtomicBoolean created = new AtomicBoolean();
        try {
            Throwable failure = catchThrowable(() -> new Schema("cleanup_failure", name, (filename, text) -> {
                created.set(true);
                // Remove only our newly created schema, forcing the cleanup connection to fail.
                dropTestSchemaIfPresent(name);
                throw original;
            }));
            assertThat(failure).isSameAs(original);
            assertThat(failure.getSuppressed()).hasSize(1);
            assertThat(failure.getSuppressed()[0]).isInstanceOf(java.sql.SQLException.class);
            assertThat(schemaExists(name)).isFalse();
        } finally {
            if (created.get()) dropTestSchemaIfPresent(name);
        }
    }

    @Test
    void assertionFailureDuringOwnershipStillDropsTheCreatedSchema() throws Exception {
        String name = PREFIX + "assert_failure_" + UUID.randomUUID().toString().replace("-", "");
        AssertionError original = new AssertionError("injected ownership assertion failure");
        AtomicBoolean created = new AtomicBoolean();
        try {
            Throwable failure = catchThrowable(() -> new Schema("assert_failure", name, (filename, text) -> {
                created.set(true);
                if (filename.endsWith("-ownership.txt")) throw original;
            }));
            assertThat(failure).isSameAs(original);
            assertThat(schemaExists(name)).isFalse();
        } finally {
            if (created.get()) dropTestSchemaIfPresent(name);
        }
    }

    @Test
    void cleanupAssertionFailureDoesNotReplaceTheOriginalEvidenceFailure() throws Exception {
        String name = PREFIX + "cleanup_assert_" + UUID.randomUUID().toString().replace("-", "");
        IOException original = new IOException("injected ownership evidence failure");
        AssertionError cleanup = new AssertionError("injected cleanup evidence assertion failure");
        AtomicBoolean created = new AtomicBoolean();
        try {
            Throwable failure = catchThrowable(() -> new Schema("cleanup_assert", name, (filename, text) -> {
                created.set(true);
                if (filename.endsWith("-ownership.txt")) throw original;
                throw cleanup;
            }));
            assertThat(failure).isSameAs(original);
            assertThat(failure.getSuppressed()).containsExactly(cleanup);
            assertThat(schemaExists(name)).isFalse();
        } finally {
            if (created.get()) dropTestSchemaIfPresent(name);
        }
    }

    @Test
    void sameExceptionInstanceDuringCleanupDoesNotMaskTheOriginalFailure() throws Exception {
        String name = PREFIX + "same_failure_" + UUID.randomUUID().toString().replace("-", "");
        IOException original = new IOException("same failure instance");
        AtomicBoolean created = new AtomicBoolean();
        try {
            Throwable failure = catchThrowable(() -> new Schema("same_failure", name, (filename, text) -> {
                created.set(true);
                throw original;
            }));
            assertThat(failure).isSameAs(original);
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(schemaExists(name)).isFalse();
        } finally {
            if (created.get()) dropTestSchemaIfPresent(name);
        }
    }

    @Test
    void failedCreateDoesNotDropAnExistingSchema() throws Exception {
        try (Schema owner = new Schema("existing_owner")) {
            owner.execute("CREATE TABLE ownership_sentinel (id INT PRIMARY KEY)");
            owner.execute("INSERT INTO ownership_sentinel VALUES (37)");
            assertThatThrownBy(() -> new Schema("existing_owner", owner.name, DatabaseMigrationTest::evidence))
                    .isInstanceOf(java.sql.SQLException.class)
                    .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
            assertThat(owner.query("SELECT id FROM ownership_sentinel")).containsExactly("37");
        }
    }

    @Test
    void unsafeSchemaNamesAreRejectedBeforeCreateOrCleanup() {
        for (String name : List.of("planlock", "planlock_f37_unknown", "planlock_f37_x_" + "a".repeat(32) + "`")) {
            assertThatThrownBy(() -> new Schema("unsafe", name, (filename, text) -> {
                throw new AssertionError("unowned schema must never reach evidence writing");
            })).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsafe F37 schema name");
        }
    }

    private static boolean schemaExists(String name) throws Exception {
        try (var admin = adminConnection(); var statement = admin.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1) != 0;
            }
        }
    }

    private static void dropTestSchemaIfPresent(String name) throws Exception {
        if (!name.matches("planlock_f37_[a-z_]+_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Unsafe F37 schema name");
        }
        try (var admin = adminConnection(); var statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS `" + name + "`");
        }
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection("jdbc:mysql://localhost:3306/?useSSL=false&allowPublicKeyRetrieval=true",
                required("F37_DB_USER"), required("F37_DB_PASSWORD"));
    }

    private static ConfigurableApplicationContext start(Schema schema, String... overrides) {
        List<String> args = new ArrayList<>(List.of("--spring.profiles.active=f37", "--server.port=0",
                "--spring.datasource.url=" + schema.url(),
                "--spring.datasource.username=" + required("F37_DB_USER"),
                "--spring.datasource.password=" + required("F37_DB_PASSWORD")));
        args.addAll(List.of(overrides));
        return new SpringApplication(PlanLockApplication.class).run(args.toArray(String[]::new));
    }

    private static void assertBusinessStructure(Schema schema) throws Exception {
        assertThat(schema.query("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schema.name + "' AND TABLE_NAME <> 'flyway_schema_history' ORDER BY TABLE_NAME"))
                .containsExactly("devices", "execution_records", "plan_strategies", "plans", "schedules", "unlock_records", "users", "violation_records");
        assertThat(schema.query("SELECT COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + schema.name + "' AND TABLE_NAME='violation_records' AND COLUMN_NAME IN ('started_at','ended_at','duration_ms','source_level') ORDER BY COLUMN_NAME"))
                .containsExactly("duration_ms", "ended_at", "source_level", "started_at");
    }

    private static void assertCreationIdentityStructure(Schema schema) throws Exception {
        assertThat(schema.query("SELECT TABLE_NAME,IS_NULLABLE,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='" + schema.name + "' AND COLUMN_NAME='client_id' AND TABLE_NAME IN ('plans','schedules') ORDER BY TABLE_NAME"))
                .containsExactly("plans|YES|utf8mb4_0900_bin", "schedules|YES|utf8mb4_0900_bin");
        assertThat(schema.query("SELECT TABLE_NAME,NON_UNIQUE,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='" + schema.name + "' AND INDEX_NAME IN ('uk_plans_user_client','uk_schedules_user_client') GROUP BY TABLE_NAME,INDEX_NAME,NON_UNIQUE ORDER BY TABLE_NAME"))
                .containsExactly("plans|0|user_id,client_id", "schedules|0|user_id,client_id");
    }

    private static List<String> history(Schema schema) throws Exception {
        List<String> rows = schema.query("SELECT version,type,success FROM flyway_schema_history ORDER BY installed_rank");
        assertThat(rows).isNotEmpty();
        evidence(schema.kind + "-history.txt", String.join("\n", rows) + "\n");
        return rows;
    }

    private static List<String> rowCounts(Schema schema) throws Exception {
        List<String> counts = new ArrayList<>();
        for (String table : List.of("devices", "execution_records", "plan_strategies", "plans", "schedules", "unlock_records", "users", "violation_records")) {
            counts.add(table + "|" + schema.query("SELECT COUNT(*) FROM " + table).getFirst());
        }
        evidence(schema.kind + "-row-counts.txt", String.join("\n", counts) + "\n");
        return counts;
    }

    private static List<String> snapshot(Schema schema, String label) throws Exception {
        String scope = " WHERE TABLE_SCHEMA='" + schema.name + "' AND TABLE_NAME <> 'flyway_schema_history'";
        List<String> rows = new ArrayList<>();
        rows.addAll(schema.query("SELECT 'COLUMN',TABLE_NAME,ORDINAL_POSITION,COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS" + scope + " ORDER BY TABLE_NAME,ORDINAL_POSITION"));
        rows.addAll(schema.query("SELECT 'INDEX',TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,COLLATION,SUB_PART,INDEX_TYPE,IS_VISIBLE FROM information_schema.STATISTICS" + scope + " ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX"));
        rows.addAll(schema.query("SELECT 'TABLE',TABLE_NAME,ENGINE,TABLE_COLLATION FROM information_schema.TABLES" + scope + " ORDER BY TABLE_NAME"));
        rows.addAll(schema.query("SELECT 'KEY',TABLE_NAME,CONSTRAINT_NAME,COLUMN_NAME,ORDINAL_POSITION,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE" + scope + " ORDER BY TABLE_NAME,CONSTRAINT_NAME,ORDINAL_POSITION"));
        rows.addAll(schema.query("SELECT 'FK',TABLE_NAME,CONSTRAINT_NAME,UPDATE_RULE,DELETE_RULE FROM information_schema.REFERENTIAL_CONSTRAINTS WHERE CONSTRAINT_SCHEMA='" + schema.name + "' ORDER BY TABLE_NAME,CONSTRAINT_NAME"));
        evidence(label + "-snapshot.txt", String.join("\n", rows) + "\n");
        evidence(label + "-comments.txt", String.join("\n", schema.query("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_COMMENT FROM information_schema.COLUMNS" + scope + " ORDER BY TABLE_NAME,ORDINAL_POSITION")) + "\n");
        evidence(label + "-table-comments.txt", String.join("\n", schema.query("SELECT TABLE_NAME,TABLE_COMMENT FROM information_schema.TABLES" + scope + " ORDER BY TABLE_NAME")) + "\n");
        System.out.println("F37 SNAPSHOT " + label + "\n" + String.join("\n", rows));
        return rows;
    }

    private static void evidence(String filename, String text) throws Exception {
        Files.createDirectories(EVIDENCE);
        Files.writeString(EVIDENCE.resolve(filename), text, StandardCharsets.UTF_8);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Required F37 environment variable missing: " + name);
        return value;
    }

    @FunctionalInterface
    private interface EvidenceWriter {
        void write(String filename, String text) throws Exception;
    }

    private static final class Schema implements AutoCloseable {
        final String kind;
        final String name;
        private boolean created;
        private final EvidenceWriter evidenceWriter;

        Schema(String kind) throws Exception {
            this(kind, PREFIX + kind + "_" + UUID.randomUUID().toString().replace("-", ""), DatabaseMigrationTest::evidence);
        }

        Schema(String kind, String name, EvidenceWriter evidenceWriter) throws Exception {
            this.kind = kind;
            this.name = name;
            if (!name.matches("planlock_f37_[a-z_]+_[0-9a-f]{32}")) {
                throw new IllegalArgumentException("Unsafe F37 schema name");
            }
            this.evidenceWriter = evidenceWriter;
            try (Connection admin = DriverManager.getConnection("jdbc:mysql://localhost:3306/?useSSL=false&allowPublicKeyRetrieval=true", required("F37_DB_USER"), required("F37_DB_PASSWORD")); var statement = admin.createStatement()) {
                statement.execute("CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                created = true;
                evidenceWriter.write(name + "-ownership.txt", "Created by DatabaseMigrationTest; exact cleanup target: " + name + "\n");
            } catch (Exception | Error failure) {
                // A failed constructor is never registered by the caller's try-with-resources.
                // Clean up only after this constructor successfully created the exact schema.
                if (created) {
                    try {
                        close();
                    } catch (Exception | Error cleanupFailure) {
                        if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
                    }
                }
                throw failure;
            }
        }

        String url() { return "jdbc:mysql://localhost:3306/" + name + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"; }
        Connection connection() throws Exception { return DriverManager.getConnection(url(), required("F37_DB_USER"), required("F37_DB_PASSWORD")); }
        void execute(String sql) throws Exception { try (var c = connection(); var s = c.createStatement()) { s.execute(sql); } }
        List<String> query(String sql) throws Exception {
            try (var c = connection(); var s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
                List<String> rows = new ArrayList<>();
                while (r.next()) {
                    List<String> values = new ArrayList<>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) values.add(r.getString(i) == null ? "<NULL>" : r.getString(i));
                    rows.add(String.join("|", values));
                }
                return rows;
            }
        }
        @Override public void close() throws Exception {
            if (!created || !name.matches("planlock_f37_[a-z_]+_[0-9a-f]{32}")) throw new IllegalStateException("Refusing cleanup of unowned schema");
            execute("DROP DATABASE `" + name + "`");
            evidenceWriter.write(name + "-cleanup.txt", "Dropped only the exact schema created by this test: " + name + "\n");
        }
    }
}
