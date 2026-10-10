package com.planlock.account.controller;

import com.planlock.account.entity.Device;
import com.planlock.account.service.DeviceService;
import com.planlock.common.ApiResponse;
import com.planlock.common.auth.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 设备管理接口（对应 04-技术实现/接口设计.md 2.2）。
 */
@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @GetMapping
    public ApiResponse<List<Device>> list() {
        return ApiResponse.ok(deviceService.list(UserContext.userId()));
    }

    @PutMapping("/{id}/primary")
    public ApiResponse<Void> setPrimary(@PathVariable Long id) {
        deviceService.setPrimary(UserContext.userId(), id);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> unbind(@PathVariable Long id) {
        deviceService.unbind(UserContext.userId(), id);
        return ApiResponse.ok();
    }
}
