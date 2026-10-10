package com.planlock.record.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.record.dto.PlanExecutionStat;
import com.planlock.record.dto.StatsResponse;
import com.planlock.record.dto.ViolationAppStat;
import com.planlock.record.entity.UnlockRecord;
import com.planlock.record.entity.ExecutionRecord;
import com.planlock.record.entity.ViolationRecord;
import com.planlock.record.mapper.ExecutionRecordMapper;
import com.planlock.record.mapper.RecordPlanRefMapper;
import com.planlock.record.mapper.UnlockRecordMapper;
import com.planlock.record.mapper.ViolationRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecordServiceStatsTest {

    private ExecutionRecordMapper executions;
    private ViolationRecordMapper violations;
    private UnlockRecordMapper unlocks;
    private RecordService service;

    @BeforeEach
    void setUp() {
        executions = mock(ExecutionRecordMapper.class);
        violations = mock(ViolationRecordMapper.class);
        unlocks = mock(UnlockRecordMapper.class);
        service = new RecordService(
                executions,
                violations,
                unlocks,
                mock(RecordPlanRefMapper.class),
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void dayReportKeepsLegacyRowsOutAndReturnsDurationRankAndPlanStates() {
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();

        List<ViolationAppStat> ranking = List.of(
                new ViolationAppStat("app.thirty", "三十", 2, 1_800_000L, 600_000L),
                new ViolationAppStat("app.twenty", "二十", 1, 1_200_000L, 0L),
                new ViolationAppStat("app.ten", "十", 1, 600_000L, 0L));
        List<PlanExecutionStat> planStats = List.of(
                new PlanExecutionStat(7L, 1, 1, 2_400_000L));
        UnlockRecord ending = new UnlockRecord();
        ending.setPlanId(7L);
        ending.setUnlockAt(start.plusHours(9));
        ending.setMethod("backup_password");

        when(executions.findCompletedBetween(9L, today, today)).thenReturn(List.of());
        when(executions.aggregateByPlanBetween(9L, today, today)).thenReturn(planStats);
        when(violations.aggregateClosedV2ByAppBetween(9L, start, end)).thenReturn(ranking);
        when(violations.countClosedV2Between(9L, start, end)).thenReturn(4);
        when(violations.sumClosedV2DurationBetween(9L, start, end)).thenReturn(3_600_000L);
        when(violations.countLegacyBetween(9L, start, end)).thenReturn(3);
        when(unlocks.findByUserBetween(9L, start, end)).thenReturn(List.of(ending));

        StatsResponse result = service.stats(9L, "day");

        assertEquals("day", result.getPeriod());
        assertEquals(4, result.getViolationCount());
        assertEquals(3_600_000L, result.getViolationDurationMs());
        assertEquals(List.of("app.thirty", "app.twenty", "app.ten"),
                result.getViolationByApp().stream().map(ViolationAppStat::getPackageName).toList());
        assertEquals(600_000L, result.getViolationByApp().getFirst().getApproxDurationMs());
        assertEquals(1, result.getPlanExecution().getFirst().getCompletedCount());
        assertEquals(1, result.getPlanExecution().getFirst().getEarlyEndedCount());
        assertEquals(2_400_000L, result.getPlanExecution().getFirst().getActualDurationMs());
        assertEquals(3, result.getLegacyViolationCount());
        assertEquals("backup_password", result.getEarlyEndings().getFirst().getMethod());
    }

    @Test
    void unknownPeriodFallsBackToWeekInsteadOfCreatingAnUnboundedRange() {
        when(executions.findCompletedBetween(eq(1L), any(), any())).thenReturn(List.of());
        when(executions.aggregateByPlanBetween(eq(1L), any(), any())).thenReturn(List.of());
        when(violations.aggregateClosedV2ByAppBetween(eq(1L), any(), any())).thenReturn(List.of());
        when(violations.countClosedV2Between(eq(1L), any(), any())).thenReturn(0);
        when(violations.sumClosedV2DurationBetween(eq(1L), any(), any())).thenReturn(0L);
        when(violations.countLegacyBetween(eq(1L), any(), any())).thenReturn(0);
        when(unlocks.findByUserBetween(eq(1L), any(), any())).thenReturn(List.of());

        assertEquals("week", service.stats(1L, "forever").getPeriod());
    }

    @Test
    void exportCarriesV2IntervalsInCsvAndJson() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 4, 9, 0);
        LocalDateTime end = start.plusMinutes(30);
        ExecutionRecord execution = new ExecutionRecord();
        execution.setPlanId(7L);
        execution.setDeviceId(3L);
        execution.setPlanDate(start.toLocalDate());
        execution.setStatus(1);
        execution.setStartedAt(start);
        execution.setEndedAt(end);
        execution.setClientId("execution-1");
        ViolationRecord violation = new ViolationRecord();
        violation.setPlanId(7L);
        violation.setDeviceId(3L);
        violation.setRecordAt(start.plusMinutes(2));
        violation.setAppName("浏览器, \"安全\"\n版");
        violation.setPackageName("app.browser");
        violation.setStartedAt(start.plusMinutes(2));
        violation.setEndedAt(start.plusMinutes(12));
        violation.setDurationMs(600_000L);
        violation.setSourceLevel("USAGE_STATS_APPROXIMATE");
        violation.setClientId("violation-1");
        when(executions.findAllByUser(9L)).thenReturn(List.of(execution));
        when(violations.findAllByUser(9L)).thenReturn(List.of(violation));
        when(unlocks.findAllByUser(9L)).thenReturn(List.of());

        String csv = service.export(9L, "csv");
        String json = service.export(9L, "json");

        assertTrue(csv.startsWith("type,plan_id,device_id,at,status,app_name,package_name,method,client_id,started_at,ended_at,duration_ms,source_level\n"));
        assertTrue(csv.contains("execution,7,3,2026-10-04,1,,,,execution-1,2026-10-04T09:00,2026-10-04T09:30,,"), csv);
        assertTrue(csv.contains("violation,7,3,2026-10-04T09:02,,\"浏览器, \"\"安全\"\"\n版\",app.browser,,violation-1,2026-10-04T09:02,2026-10-04T09:12,600000,USAGE_STATS_APPROXIMATE"), csv);
        assertTrue(json.contains("\"durationMs\":600000"));
    }
}
