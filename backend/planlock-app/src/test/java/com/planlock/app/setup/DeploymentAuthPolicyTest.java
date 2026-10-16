package com.planlock.app.setup;

import com.planlock.account.dto.*;
import com.planlock.account.mapper.*;
import com.planlock.account.service.*;
import com.planlock.common.BusinessException;
import com.planlock.common.auth.JwtUtil;
import com.planlock.account.entity.Device;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentAuthPolicyTest {
    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RegistrationPolicyTest.Config.class)
            .withPropertyValues("planlock.deployment.enabled=true");

    @Test void codeIssuanceIsRejectedBeforeAnyStorageAccess() {
        runner.run(ctx -> {
            clearInvocations(ctx.getBean(StringRedisTemplate.class));
            var req = new SendCodeRequest(); req.setAccount("owner@example.org");
            assertThatThrownBy(() -> ctx.getBean(AuthService.class).sendCode(req))
                    .isInstanceOf(BusinessException.class).hasMessage("自建实例不支持验证码，请使用账号密码");
            verifyNoInteractions(ctx.getBean(VerificationCodeService.class), ctx.getBean(UserMapper.class), ctx.getBean(StringRedisTemplate.class));
        });
    }

    @Test void codeLoginIncludingPasswordIsRejectedBeforeAnyStorageAccess() {
        runner.run(ctx -> {
            clearInvocations(ctx.getBean(StringRedisTemplate.class));
            var req = new LoginRequest(); req.setAccount("owner@example.org");
            req.setCode("123456"); req.setPassword("valid-password");
            assertThatThrownBy(() -> ctx.getBean(AuthService.class).login(req))
                    .isInstanceOf(BusinessException.class).hasMessage("自建实例不支持验证码，请使用账号密码");
            verifyNoInteractions(ctx.getBean(VerificationCodeService.class), ctx.getBean(UserMapper.class), ctx.getBean(DeviceMapper.class), ctx.getBean(StringRedisTemplate.class));
        });
    }

    @Test void codeResetIsRejectedBeforeAnyStorageAccess() {
        runner.run(ctx -> {
            clearInvocations(ctx.getBean(StringRedisTemplate.class));
            var req = new BackupPasswordResetRequest(); req.setAccount("owner@example.org"); req.setCode("123456");
            assertThatThrownBy(() -> ctx.getBean(AuthService.class).resetBackupPassword(req))
                    .isInstanceOf(BusinessException.class).hasMessage("自建实例不支持验证码，请使用账号密码");
            verifyNoInteractions(ctx.getBean(VerificationCodeService.class), ctx.getBean(UserMapper.class), ctx.getBean(StringRedisTemplate.class));
        });
    }

    @Test void unknownDeviceCannotCreateAnAnonymousUser() {
        runner.run(ctx -> {
            var req = new DeviceRegisterRequest(); req.setDeviceUid("new-device");
            assertThatThrownBy(() -> ctx.getBean(AuthService.class).deviceRegister(req))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("账号密码");
            verify(ctx.getBean(UserMapper.class), never()).insert(any());
            verify(ctx.getBean(DeviceMapper.class), never()).insert(any());
        });
    }

    @Test void anonymousExistingDeviceCannotMintAnOwnerToken() {
        runner.run(ctx -> {
            var req = new DeviceRegisterRequest(); req.setDeviceUid("known-owner-device");
            assertThatThrownBy(() -> ctx.getBean(AuthService.class).deviceRegister(req)).hasMessageContaining("账号密码");
            verifyNoInteractions(ctx.getBean(DeviceMapper.class), ctx.getBean(UserMapper.class), ctx.getBean(JwtUtil.class));
        });
    }

    @Test void touchRequiresBothMatchingUserAndDeviceClaims() {
        runner.run(ctx -> {
            var jwt = ctx.getBean(JwtUtil.class); var devices = ctx.getBean(DeviceMapper.class);
            var req = new DeviceRegisterRequest(); req.setDeviceUid("known-owner-device");
            var device = new Device(); device.setUserId(42L); device.setId(7L);
            when(devices.findByDeviceUid("known-owner-device")).thenReturn(device);
            var request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer test-token");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            for (var identity : java.util.List.of(java.util.Map.of("uid", 43L, "did", 7L), java.util.Map.of("uid", 42L, "did", 8L))) {
                when(jwt.parse("test-token")).thenReturn(io.jsonwebtoken.Jwts.claims().add(identity).build());
                assertThatThrownBy(() -> ctx.getBean(AuthService.class).deviceRegister(req)).hasMessageContaining("账号密码");
            }
            verify(devices, never()).touch(any(), any()); verify(jwt, never()).generate(any(), any());
            when(jwt.parse("test-token")).thenReturn(io.jsonwebtoken.Jwts.claims().add(java.util.Map.of("uid", 42L, "did", 7L)).build());
            assertThat(ctx.getBean(AuthService.class).deviceRegister(req).getUserId()).isEqualTo(42L);
            verify(devices).touch(eq(7L), any()); verify(jwt).generate(42L, 7L);
            verifyNoInteractions(ctx.getBean(UserMapper.class)); verify(devices, never()).insert(any());
        });
    }

    @Test void invalidBearerIsRejectedBeforeLookingUpDevices() {
        runner.run(ctx -> {
            var request = new MockHttpServletRequest(); request.addHeader("Authorization", "Bearer expired");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            when(ctx.getBean(JwtUtil.class).parse("expired")).thenThrow(new IllegalArgumentException("expired"));
            assertThatThrownBy(() -> ctx.getBean(AuthService.class).deviceRegister(new DeviceRegisterRequest())).hasMessageContaining("账号密码");
            verifyNoInteractions(ctx.getBean(DeviceMapper.class), ctx.getBean(UserMapper.class));
        });
    }
}
