package com.planlock.record.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import com.planlock.record.dto.DailyStat;
import com.planlock.record.dto.EarlyEnding;
import com.planlock.record.dto.RecordHistoryResponse;
import com.planlock.record.dto.ExecutionRecordRequest;
import com.planlock.record.dto.StatsResponse;
import com.planlock.record.dto.TodaySummary;
import com.planlock.record.dto.UnlockRecordRequest;
import com.planlock.record.dto.ViolationRecordRequest;
import com.planlock.record.entity.ExecutionRecord;
import com.planlock.record.entity.UnlockRecord;
import com.planlock.record.entity.ViolationRecord;
import com.planlock.record.mapper.ExecutionRecordMapper;
import com.planlock.record.mapper.RecordPlanRefMapper;
import com.planlock.record.mapper.UnlockRecordMapper;
import com.planlock.record.mapper.ViolationRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 记录与统计服务（record 模块）。
 */
@Service
@RequiredArgsConstructor
public class RecordService {

    private final ExecutionRecordMapper executionMapper;
    private final ViolationRecordMapper violationMapper;
    private final UnlockRecordMapper unlockMapper;
    private final RecordPlanRefMapper planRefMapper;
    private final ObjectMapper objectMapper;

    public RecordHistoryResponse history(Long userId, long executionAfterId, long unlockAfterId, int limit) {
        return history(userId, executionAfterId, unlockAfterId, -1, limit);
    }

    public RecordHistoryResponse history(Long userId, long executionAfterId, long unlockAfterId, long behaviorAfterId, int limit) {
        int pageSize = Math.clamp(limit, 1, 1000);
        long executionCursor = Math.max(0, executionAfterId);
        long unlockCursor = Math.max(0, unlockAfterId);
        var executionPage = executionMapper.findHistoryAfter(userId, executionCursor, pageSize + 1);
        var unlockPage = unlockMapper.findHistoryAfter(userId, unlockCursor, pageSize + 1);
        // Old clients omit behaviorAfterId: do not let an unconsumed table hold their hasMore open.
        var behaviorPage = behaviorAfterId < 0 ? List.<ViolationRecord>of()
                : violationMapper.findHistoryAfter(userId, Math.max(0, behaviorAfterId), pageSize + 1);
        boolean hasMore = executionPage.size() > pageSize || unlockPage.size() > pageSize || behaviorPage.size() > pageSize;
        var behaviors = behaviorPage.stream().limit(pageSize).toList();
        var executions = executionPage.stream().limit(pageSize).toList();
        var unlocks = unlockPage.stream().limit(pageSize)
                .map(u -> new RecordHistoryResponse.UnlockHistory(u.getId(), u.getPlanId(), u.getUnlockAt(), u.getMethod(), u.getClientId()))
                .toList();
        if (!executions.isEmpty()) executionCursor = executions.getLast().getId();
        if (!unlocks.isEmpty()) unlockCursor = unlocks.getLast().id();
        long behaviorCursor = behaviors.isEmpty() ? Math.max(0, behaviorAfterId) : behaviors.getLast().getId();
        return new RecordHistoryResponse(executions, unlocks, executionCursor, unlockCursor, hasMore, behaviors, behaviorCursor);
    }

    @Transactional
    public void reportExecution(Long userId, Long deviceId, ExecutionRecordRequest req) {
        lockHistoryOwner(userId);
        requireOwnedPlan(userId, req.getPlanId());
        ExecutionRecord record = new ExecutionRecord();
        record.setPlanId(req.getPlanId());
        record.setDeviceId(deviceId);
        record.setPlanDate(req.getPlanDate());
        record.setStatus(req.getStatus());
        record.setStartedAt(req.getStartedAt());
        record.setEndedAt(req.getEndedAt());
        record.setClientId(req.getClientId());
        insertIdempotent(() -> executionMapper.insert(record));
    }

    @Transactional
    public void reportViolation(Long userId, Long deviceId, ViolationRecordRequest req) {
        lockHistoryOwner(userId);
        requireOwnedPlan(userId, req.getPlanId());
        ViolationRecord record = new ViolationRecord();
        record.setPlanId(req.getPlanId());
        record.setDeviceId(deviceId);
        record.setRecordAt(req.getRecordAt());
        record.setAppName(req.getAppName());
        record.setPackageName(req.getPackageName());
        record.setStartedAt(req.getStartedAt());
        record.setEndedAt(req.getEndedAt());
        record.setDurationMs(req.getDurationMs());
        record.setSourceLevel(req.getSourceLevel());
        record.setClientId(req.getClientId());
        insertIdempotent(() -> violationMapper.insert(record));
    }

    @Transactional
    public void reportUnlock(Long userId, Long deviceId, UnlockRecordRequest req) {
        lockHistoryOwner(userId);
        requireOwnedPlan(userId, req.getPlanId());
        UnlockRecord record = new UnlockRecord();
        record.setPlanId(req.getPlanId());
        record.setDeviceId(deviceId);
        record.setUnlockAt(req.getUnlockAt());
        record.setMethod(req.getMethod() == null ? "backup_password" : req.getMethod());
        record.setClientId(req.getClientId());
        insertIdempotent(() -> unlockMapper.insert(record));
    }

    public TodaySummary today(Long userId) {
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.atStartOfDay();
        LocalDateTime end = today.plusDays(1).atStartOfDay();
        return TodaySummary.builder()
                .date(today)
                .executions(executionMapper.findByUserAndDate(userId, today))
                .violationCount(violationMapper.findByUserBetween(userId, start, end).size())
                .unlockCount(unlockMapper.findByUserBetween(userId, start, end).size())
                .build();
    }

    public StatsResponse stats(Long userId, String period) {
        String normalizedPeriod = switch (period == null ? "week" : period.toLowerCase(Locale.ROOT)) {
            case "day" -> "day";
            case "month" -> "month";
            default -> "week";
        };
        LocalDate today = LocalDate.now();
        LocalDate start = switch (normalizedPeriod) {
            case "day" -> today;
            case "month" -> today.withDayOfMonth(1);
            default -> today.minusDays(6);
        };
        LocalDateTime rangeStart = start.atStartOfDay();
        LocalDateTime rangeEnd = today.plusDays(1).atStartOfDay();

        List<ExecutionRecord> completed = executionMapper.findCompletedBetween(userId, start, today);
        Map<LocalDate, Long> perDay = completed.stream()
                .collect(Collectors.groupingBy(ExecutionRecord::getPlanDate, Collectors.counting()));

        List<DailyStat> daily = perDay.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> DailyStat.builder().date(e.getKey()).completed(e.getValue().intValue()).build())
                .toList();

        int continuousDays = 0;
        LocalDate cursor = today;
        if (!perDay.containsKey(cursor)) {
            cursor = cursor.minusDays(1);
        }
        while (perDay.containsKey(cursor)) {
            continuousDays++;
            cursor = cursor.minusDays(1);
        }

        List<EarlyEnding> earlyEndings = unlockMapper.findByUserBetween(userId, rangeStart, rangeEnd).stream()
                .limit(50)
                .map(item -> new EarlyEnding(item.getUnlockAt(), item.getPlanId(), item.getMethod()))
                .toList();

        return StatsResponse.builder()
                .period(normalizedPeriod)
                .continuousDays(continuousDays)
                .completedCount(completed.size())
                .violationCount(violationMapper.countClosedV2Between(userId, rangeStart, rangeEnd))
                .violationDurationMs(violationMapper.sumClosedV2DurationBetween(userId, rangeStart, rangeEnd))
                .violationByApp(violationMapper.aggregateClosedV2ByAppBetween(userId, rangeStart, rangeEnd))
                .planExecution(executionMapper.aggregateByPlanBetween(userId, start, today))
                .earlyEndings(earlyEndings)
                .legacyViolationCount(violationMapper.countLegacyBetween(userId, rangeStart, rangeEnd))
                .unlockCount(unlockMapper.findByUserBetween(userId, rangeStart, rangeEnd).size())
                .daily(daily)
                .build();
    }

    public String export(Long userId, String format) {
        List<ExecutionRecord> executions = executionMapper.findAllByUser(userId);
        List<ViolationRecord> violations = violationMapper.findAllByUser(userId);
        List<UnlockRecord> unlocks = unlockMapper.findAllByUser(userId);

        if ("csv".equalsIgnoreCase(format)) {
            return toCsv(executions, violations, unlocks);
        }
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "executions", executions,
                    "violations", violations,
                    "unlocks", unlocks));
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "导出 JSON 生成失败");
        }
    }

    private String toCsv(List<ExecutionRecord> executions,
                         List<ViolationRecord> violations,
                         List<UnlockRecord> unlocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("type,plan_id,device_id,at,status,app_name,package_name,method,client_id,started_at,ended_at,duration_ms,source_level\n");
        for (ExecutionRecord e : executions) {
            sb.append("execution,").append(e.getPlanId()).append(',').append(e.getDeviceId()).append(',')
                    .append(e.getPlanDate()).append(',').append(e.getStatus()).append(",,,,")
                    .append(csv(e.getClientId())).append(',').append(value(e.getStartedAt())).append(',')
                    .append(value(e.getEndedAt())).append(",,\n");
        }
        for (ViolationRecord v : violations) {
            sb.append("violation,").append(v.getPlanId()).append(',').append(v.getDeviceId()).append(',')
                    .append(v.getRecordAt()).append(",,").append(csv(v.getAppName())).append(',')
                    .append(csv(v.getPackageName())).append(",,").append(csv(v.getClientId())).append(',')
                    .append(value(v.getStartedAt())).append(',').append(value(v.getEndedAt())).append(',')
                    .append(value(v.getDurationMs())).append(',').append(csv(v.getSourceLevel())).append('\n');
        }
        for (UnlockRecord u : unlocks) {
            sb.append("unlock,").append(u.getPlanId()).append(',').append(u.getDeviceId()).append(',')
                    .append(u.getUnlockAt()).append(",,,,").append(csv(u.getMethod())).append(',')
                    .append(csv(u.getClientId())).append(",,,,\n");
        }
        return sb.toString();
    }

    private String value(Object value) {
        return value == null ? "" : value.toString();
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private void requireOwnedPlan(Long userId, Long planId) {
        if (planRefMapper.countOwnedPlan(planId, userId) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "计划不存在");
        }
    }

    private void lockHistoryOwner(Long userId) {
        // ID cursors require commit order within an owner; AUTO_INCREMENT alone does not provide it.
        if (planRefMapper.lockOwner(userId) == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
    }

    private void insertIdempotent(Runnable insert) {
        try {
            insert.run();
        } catch (DuplicateKeyException e) {
            // client_id 幂等：重复提交忽略
        }
    }
}
