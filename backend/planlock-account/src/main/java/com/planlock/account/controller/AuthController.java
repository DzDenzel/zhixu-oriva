package com.planlock.account.controller;

import com.planlock.account.dto.AuthResponse;
import com.planlock.account.dto.DeviceRegisterRequest;
import com.planlock.account.dto.LoginRequest;
import com.planlock.account.dto.MergeRequest;
import com.planlock.account.dto.RegisterRequest;
import com.planlock.account.dto.SendCodeRequest;
import com.planlock.account.service.AuthService;
import com.planlock.common.ApiResponse;
import com.planlock.common.auth.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证与账号接口（对应 04-技术实现/接口设计.md 2.1）。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/device")
    public ApiResponse<AuthResponse> deviceRegister(@Valid @RequestBody DeviceRegisterRequest req) {
        return ApiResponse.ok(authService.deviceRegister(req));
    }

    @PostMapping("/code/send")
    public ApiResponse<Void> sendCode(@Valid @RequestBody SendCodeRequest req) {
        authService.sendCode(req);
        return ApiResponse.ok();
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ApiResponse.ok(authService.register(req));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req));
    }

    @PostMapping("/merge")
    public ApiResponse<Map<String, Long>> merge(@Valid @RequestBody MergeRequest req) {
        return ApiResponse.ok(authService.merge(UserContext.userId(), req.getGuestUserId(), req.getGuestToken()));
    }
}
