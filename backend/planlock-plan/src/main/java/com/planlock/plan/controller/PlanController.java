package com.planlock.plan.controller;

import com.planlock.common.ApiResponse;
import com.planlock.common.auth.UserContext;
import com.planlock.plan.dto.PlanResponse;
import com.planlock.plan.dto.PlanUpsertRequest;
import com.planlock.plan.service.PlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 计划接口（对应 04-技术实现/接口设计.md 2.3）。
 */
@RestController
@RequestMapping("/api/v1/plans")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;

    @GetMapping
    public ApiResponse<List<PlanResponse>> list() {
        return ApiResponse.ok(planService.list(UserContext.userId()));
    }

    @PostMapping
    public ApiResponse<PlanResponse> create(@Valid @RequestBody PlanUpsertRequest req) {
        return ApiResponse.ok(planService.create(UserContext.userId(), req));
    }

    @PutMapping("/{id}")
    public ApiResponse<PlanResponse> update(@PathVariable Long id,
                                            @RequestParam Long baseVersion,
                                            @Valid @RequestBody PlanUpsertRequest req) {
        return ApiResponse.ok(planService.update(UserContext.userId(), id, req, baseVersion));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        planService.delete(UserContext.userId(), id);
        return ApiResponse.ok();
    }

    @PostMapping("/{id}/pause")
    public ApiResponse<PlanResponse> pause(@PathVariable Long id) {
        return ApiResponse.ok(planService.pause(UserContext.userId(), id));
    }

    @GetMapping("/export")
    public ResponseEntity<String> exportIcs() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/calendar"))
                .body(planService.exportIcs(UserContext.userId()));
    }
}
