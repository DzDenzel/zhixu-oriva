package com.planlock.record.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.record.dto.RhythmSummary;
import com.planlock.record.dto.RhythmInputs;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class RhythmStatsTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test void sharedGoldenFixtureMatchesEveryField() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.exists(root.resolve("test-fixtures/rhythm-golden.json"))) root = root.getParent();
        var fixture = json.readTree(root.resolve("test-fixtures/rhythm-golden.json").toFile());
        for (var test : fixture.path("cases")) {
            var plans = json.readerForListOf(RhythmInputs.Plan.class).<List<RhythmInputs.Plan>>readValue(test.path("plans"));
            var executions = json.readerForListOf(RhythmInputs.Execution.class).<List<RhythmInputs.Execution>>readValue(test.path("executions"));
            var behavior = json.readerForListOf(RhythmInputs.Behavior.class).<List<RhythmInputs.Behavior>>readValue(test.path("behavior"));
            var actual = RhythmStats.aggregate(plans, executions, behavior, LocalDate.of(2026, 10, 4), 62, "Synced", json);
            assertThat(json.readTree(json.writeValueAsString(actual))).as(test.path("name").asText()).isEqualTo(test.path("expected"));
        }
    }

    @Test void invalidCalendarWindowIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> RhythmStats.aggregate(List.of(), List.of(), List.of(),
                LocalDate.of(2026, 10, 4), 0, "Synced", json));
    }

    @Test void ownAppIsExcludedAndExpandedCalendarDoesNotChangeSummaryWindows() {
        var today = LocalDate.of(2026, 10, 4);
        var own = new RhythmInputs.Behavior(1L, "host", "知序", "com.planlock.app", today.atTime(9, 0), today.atTime(10, 0), 3_600_000L);
        var shortWindow = RhythmStats.aggregate(List.of(), List.of(), List.of(own), today, 30, "Synced", json);
        var expanded = RhythmStats.aggregate(List.of(), List.of(), List.of(own), today, 62, "Synced", json);
        assertThat(expanded.distraction()).isNull();
        assertThat(expanded.days()).hasSize(62);
        assertThat(expanded.days().subList(32, 62)).isEqualTo(shortWindow.days());
        assertThat(expanded.focusMinutes30d()).isEqualTo(shortWindow.focusMinutes30d());
        assertThat(expanded.completionRate7d()).isEqualTo(shortWindow.completionRate7d());
    }
}
