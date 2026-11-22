package com.planlock.account.service;

import com.planlock.account.entity.Device;
import com.planlock.account.mapper.DeviceMapper;
import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeviceService 单元测试（覆盖在线窗口计算、主设备切换顺序与归属越权拦截）。
 */
@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock
    private DeviceMapper deviceMapper;

    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        deviceService = new DeviceService(deviceMapper);
    }

    @Test
    void listComputesOnlineStatusWithinFiveMinuteWindow() {
        Device onlineDevice = device(1L, 10L, LocalDateTime.now().minusMinutes(2));
        Device offlineDevice = device(2L, 10L, LocalDateTime.now().minusMinutes(8));
        Device neverOnlineDevice = device(3L, 10L, null);
        when(deviceMapper.findByUserId(10L)).thenReturn(List.of(onlineDevice, offlineDevice, neverOnlineDevice));

        List<Device> result = deviceService.list(10L);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).getOnline()).isTrue();
        assertThat(result.get(1).getOnline()).isFalse();
        assertThat(result.get(2).getOnline()).isFalse();
    }

    @Test
    void setPrimaryClearsOldPrimaryBeforeSettingNewPrimary() {
        when(deviceMapper.findById(2L)).thenReturn(device(2L, 10L, LocalDateTime.now()));

        deviceService.setPrimary(10L, 2L);

        InOrder order = inOrder(deviceMapper);
        order.verify(deviceMapper).clearPrimary(10L);
        order.verify(deviceMapper).setPrimary(2L);
    }

    @Test
    void setPrimaryRejectsMissingOrForeignDevice() {
        when(deviceMapper.findById(99L)).thenReturn(null);
        assertNotFound(() -> deviceService.setPrimary(10L, 99L));

        when(deviceMapper.findById(2L)).thenReturn(device(2L, 88L, LocalDateTime.now()));
        assertNotFound(() -> deviceService.setPrimary(10L, 2L));

        verify(deviceMapper, never()).clearPrimary(any());
        verify(deviceMapper, never()).setPrimary(any());
    }

    @Test
    void unbindDeletesOwnedDeviceAndRejectsForeignDevice() {
        when(deviceMapper.findById(3L)).thenReturn(device(3L, 10L, LocalDateTime.now()));
        deviceService.unbind(10L, 3L);
        verify(deviceMapper).delete(3L);

        when(deviceMapper.findById(4L)).thenReturn(device(4L, 77L, LocalDateTime.now()));
        assertNotFound(() -> deviceService.unbind(10L, 4L));
        verify(deviceMapper, never()).delete(4L);
    }

    private static Device device(Long id, Long userId, LocalDateTime lastOnlineAt) {
        Device d = new Device();
        d.setId(id);
        d.setUserId(userId);
        d.setLastOnlineAt(lastOnlineAt);
        return d;
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo(ErrorCode.NOT_FOUND.getCode()));
    }
}
