package com.planlock.account.controller;

import com.planlock.account.dto.BackupPasswordResetRequest;
import com.planlock.account.dto.BackupPasswordSetRequest;
import com.planlock.account.dto.MeResponse;
import com.planlock.account.service.AuthService;
import com.planlock.common.ApiResponse;
import com.planlock.common.auth.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户接口（只读身份摘要与备用密码设置 / 找回）。
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final AuthService authService;

    @GetMapping
    public ResponseEntity<ApiResponse<MeResponse>> me() {
        MeResponse profile = authService.getCurrentProfile(UserContext.userId(), UserContext.deviceId());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ApiResponse.ok(profile));
    }

    @PutMapping("/backup-password")
    public ApiResponse<Void> setBackupPassword(@Valid @RequestBody BackupPasswordSetRequest req) {
        authService.setBackupPassword(UserContext.userId(), req);
        return ApiResponse.ok();
    }

    @PostMapping("/backup-password/reset")
    public ApiResponse<Void> resetBackupPassword(@Valid @RequestBody BackupPasswordResetRequest req) {
        authService.resetBackupPassword(req);
        return ApiResponse.ok();
    }
}
