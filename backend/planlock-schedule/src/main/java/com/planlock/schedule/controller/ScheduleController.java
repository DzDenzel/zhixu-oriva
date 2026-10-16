package com.planlock.schedule.controller;

import com.planlock.common.ApiResponse;
import com.planlock.common.auth.UserContext;
import com.planlock.schedule.dto.ScheduleResponse;
import com.planlock.schedule.dto.ScheduleUpsertRequest;
import com.planlock.schedule.service.ScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/v1/schedules") @RequiredArgsConstructor
public class ScheduleController {
    private final ScheduleService service;
    @GetMapping public ApiResponse<List<ScheduleResponse>> list(){return ApiResponse.ok(service.list(UserContext.userId()));}
    @PostMapping public ApiResponse<ScheduleResponse> create(@Valid @RequestBody ScheduleUpsertRequest req){return ApiResponse.ok(service.create(UserContext.userId(),req));}
    @PutMapping("/{id}") public ApiResponse<ScheduleResponse> update(@PathVariable Long id,@RequestParam Long baseVersion,@Valid @RequestBody ScheduleUpsertRequest req){return ApiResponse.ok(service.update(UserContext.userId(),id,baseVersion,req));}
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable Long id,@RequestParam(required=false) Long baseVersion){service.delete(UserContext.userId(),id,baseVersion);return ApiResponse.ok();}
}
