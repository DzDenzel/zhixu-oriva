package com.planlock.app.setup;

import com.planlock.account.dto.*;
import com.planlock.account.entity.*;
import com.planlock.account.mapper.*;
import com.planlock.account.service.*;
import com.planlock.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevelopmentAuthCompatibilityTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RegistrationPolicyTest.Config.class);

    @Test void developmentStillIssuesCodesAndLogsInWithCode() {
        runner.run(ctx -> {
            var service = ctx.getBean(AuthService.class); var codes = ctx.getBean(VerificationCodeService.class);
            clearInvocations(ctx.getBean(StringRedisTemplate.class));
            var send = new SendCodeRequest(); send.setAccount("dev@example.org"); service.sendCode(send);
            verify(codes).issue("dev@example.org");
            var user = new User(); user.setId(42L);
            when(ctx.getBean(UserMapper.class).findByAccount("dev@example.org")).thenReturn(user);
            when(codes.verify("dev@example.org", "123456")).thenReturn(true);
            var req = new LoginRequest(); req.setAccount("dev@example.org"); req.setCode("123456"); req.setDeviceUid("dev");
            assertThat(service.login(req).getUserId()).isEqualTo(42L);
            verifyNoInteractions(ctx.getBean(StringRedisTemplate.class));
        });
    }

    @Test void developmentStillCreatesAnonymousUsersAndTouchesExistingDevices() {
        runner.run(ctx -> {
            var users = ctx.getBean(UserMapper.class); var devices = ctx.getBean(DeviceMapper.class);
            doAnswer(call -> { ((User) call.getArgument(0)).setId(42L); return 1; }).when(users).insert(any());
            var req = new DeviceRegisterRequest(); req.setDeviceUid("dev");
            assertThat(ctx.getBean(AuthService.class).deviceRegister(req).getUserId()).isEqualTo(42L);
            var existing = new Device(); existing.setId(7L); existing.setUserId(42L);
            var anonymous = new User(); anonymous.setId(42L);
            when(users.findById(42L)).thenReturn(anonymous);
            when(devices.findByDeviceUid("dev")).thenReturn(existing);
            assertThat(ctx.getBean(AuthService.class).deviceRegister(req).getDeviceId()).isEqualTo(7L);
            verify(users, times(1)).insert(any()); verify(devices).touch(eq(7L), any());
        });
    }

    @Test void reinstallDeviceRegistrationCannotIssueThePreviousAccountToken() {
        runner.run(ctx -> {
            var users=ctx.getBean(UserMapper.class);var devices=ctx.getBean(DeviceMapper.class);
            var member=new User();member.setId(9L);member.setEmail("member@example.invalid");
            when(users.findById(9L)).thenReturn(member);
            var device=new Device();device.setId(7L);device.setUserId(9L);device.setDeviceUid("reinstall");
            when(devices.findByDeviceUid("reinstall")).thenReturn(device);
            doAnswer(call->{((User)call.getArgument(0)).setId(10L);return 1;}).when(users).insert(any());
            var request=new DeviceRegisterRequest();request.setDeviceUid("reinstall");
            assertThat(ctx.getBean(AuthService.class).deviceRegister(request).getUserId()).isEqualTo(10L);
            verify(devices).update(argThat(value->value.getUserId().equals(10L)));
        });
    }

    @Test void developmentStillResetsByCodeAndPreservesUnknownAccountError() {
        runner.run(ctx -> {
            var user = new User(); user.setId(42L);
            var users = ctx.getBean(UserMapper.class); when(users.findByAccount("dev@example.org")).thenReturn(user);
            when(ctx.getBean(VerificationCodeService.class).verify("dev@example.org", "123456")).thenReturn(true);
            var req = new BackupPasswordResetRequest(); req.setAccount("dev@example.org"); req.setCode("123456"); req.setNewBackupPassword("new-backup");
            clearInvocations(ctx.getBean(StringRedisTemplate.class));
            ctx.getBean(AuthService.class).resetBackupPassword(req);
            verify(users).updateBackupPassword(eq(42L), anyString(), isNull(), isNull());
            verifyNoInteractions(ctx.getBean(StringRedisTemplate.class));
            req.setAccount("missing@example.org");
            assertThatThrownBy(() -> ctx.getBean(AuthService.class).resetBackupPassword(req))
                    .isInstanceOf(BusinessException.class).hasMessage("账号不存在");
        });
    }
}
