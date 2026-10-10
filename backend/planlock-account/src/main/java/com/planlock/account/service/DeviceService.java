package com.planlock.account.service;

import com.planlock.account.entity.Device;
import com.planlock.account.mapper.DeviceMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 设备管理服务（account 模块）。
 */
@Service
@RequiredArgsConstructor
public class DeviceService {

    private static final long ONLINE_WINDOW_MINUTES = 5;

    private final DeviceMapper deviceMapper;

    public List<Device> list(Long userId) {
        List<Device> devices = deviceMapper.findByUserId(userId);
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(ONLINE_WINDOW_MINUTES);
        for (Device device : devices) {
            device.setOnline(device.getLastOnlineAt() != null && device.getLastOnlineAt().isAfter(threshold));
        }
        return devices;
    }

    @Transactional
    public void setPrimary(Long userId, Long deviceId) {
        requireOwnedDevice(userId, deviceId);
        deviceMapper.clearPrimary(userId);
        deviceMapper.setPrimary(deviceId);
    }

    @Transactional
    public void unbind(Long userId, Long deviceId) {
        requireOwnedDevice(userId, deviceId);
        deviceMapper.delete(deviceId);
    }

    private Device requireOwnedDevice(Long userId, Long deviceId) {
        Device device = deviceMapper.findById(deviceId);
        if (device == null || !device.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "设备不存在");
        }
        return device;
    }
}
