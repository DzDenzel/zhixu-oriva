package com.planlock.record.dto;

import com.planlock.record.entity.ExecutionRecord;
import com.planlock.record.entity.ViolationRecord;
import java.time.LocalDateTime;
import java.util.List;

/** Append-only history; each table has its own server-id cursor. */
public record RecordHistoryResponse(List<ExecutionRecord> executions, List<UnlockHistory> unlocks,
                                    long executionCursor, long unlockCursor, boolean hasMore,
                                    List<ViolationRecord> behaviors, long behaviorCursor) {
    public RecordHistoryResponse(List<ExecutionRecord> executions, List<UnlockHistory> unlocks,
                                 long executionCursor, long unlockCursor, boolean hasMore) {
        this(executions, unlocks, executionCursor, unlockCursor, hasMore, List.of(), 0);
    }
    public record UnlockHistory(Long id, Long planId, LocalDateTime unlockAt, String method, String clientId) { }
}
