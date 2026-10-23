package com.planlock.record.controller;

import com.planlock.common.ApiResponse;
import com.planlock.common.auth.UserContext;
import com.planlock.record.dto.ExecutionRecordRequest;
import com.planlock.record.dto.RecordHistoryResponse;
import com.planlock.record.dto.StatsResponse;
import com.planlock.record.dto.TodaySummary;
import com.planlock.record.dto.UnlockRecordRequest;
import com.planlock.record.dto.ViolationRecordRequest;
import com.planlock.record.service.RecordService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 记录与统计接口（对应 04-技术实现/接口设计.md 2.5）。
 */
@RestController
@RequestMapping("/api/v1/records")
@RequiredArgsConstructor
public class RecordController {

    private final RecordService recordService;

    /** Called by the existing sync cycle, never by statistics pages. */
    @GetMapping("/history")
    public ApiResponse<RecordHistoryResponse> history(@RequestParam(defaultValue = "0") long executionAfterId,
                                                     @RequestParam(defaultValue = "0") long unlockAfterId,
                                                     @RequestParam(required = false) Long behaviorAfterId,
                                                     @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.ok(recordService.history(UserContext.userId(), executionAfterId, unlockAfterId,
                behaviorAfterId == null ? -1 : Math.max(0, behaviorAfterId), limit));
    }

    @PostMapping("/execution")
    public ApiResponse<Void> reportExecution(@Valid @RequestBody ExecutionRecordRequest req) {
        recordService.reportExecution(UserContext.userId(), UserContext.deviceId(), req);
        return ApiResponse.ok();
    }

    @PostMapping("/violation")
    public ApiResponse<Void> reportViolation(@Valid @RequestBody ViolationRecordRequest req) {
        recordService.reportViolation(UserContext.userId(), UserContext.deviceId(), req);
        return ApiResponse.ok();
    }

    @PostMapping("/unlock")
    public ApiResponse<Void> reportUnlock(@Valid @RequestBody UnlockRecordRequest req) {
        recordService.reportUnlock(UserContext.userId(), UserContext.deviceId(), req);
        return ApiResponse.ok();
    }

    @GetMapping("/today")
    public ApiResponse<TodaySummary> today() {
        return ApiResponse.ok(recordService.today(UserContext.userId()));
    }

    @GetMapping("/stats")
    @Deprecated(since = "2.1.1", forRemoval = false)
    public ApiResponse<StatsResponse> stats(@RequestParam(defaultValue = "week") String period) {
        return ApiResponse.ok(recordService.stats(UserContext.userId(), period));
    }

    @GetMapping("/export")
    public ResponseEntity<String> export(@RequestParam(defaultValue = "json") String format) {
        String body = recordService.export(UserContext.userId(), format);
        MediaType mediaType = "csv".equalsIgnoreCase(format) ? MediaType.TEXT_PLAIN : MediaType.APPLICATION_JSON;
        return ResponseEntity.ok().contentType(mediaType).body(body);
    }
}
