package com.planlock.sync.controller;

import com.planlock.common.ApiResponse;
import com.planlock.common.auth.UserContext;
import com.planlock.sync.dto.SyncPullResponse;
import com.planlock.sync.dto.SyncPushRequest;
import com.planlock.sync.dto.SyncPushResponse;
import com.planlock.sync.service.SyncService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 同步接口（对应 04-技术实现/接口设计.md 2.6）。
 */
@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    @GetMapping("/pull")
    public ApiResponse<SyncPullResponse> pull() {
        return ApiResponse.ok(syncService.pull(UserContext.userId()));
    }

    @PostMapping("/push")
    public ApiResponse<SyncPushResponse> push(@Valid @RequestBody SyncPushRequest req) {
        return ApiResponse.ok(syncService.push(UserContext.userId(), req));
    }
}
