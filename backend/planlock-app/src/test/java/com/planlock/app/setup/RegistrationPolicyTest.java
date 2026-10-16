package com.planlock.app.setup;

import com.planlock.account.service.*;
import com.planlock.account.mapper.*;
import com.planlock.account.dto.RegisterRequest;
import com.planlock.common.BusinessException;
import com.planlock.common.auth.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class RegistrationPolicyTest {
    @Configuration @Import({AuthService.class, DeploymentAuthLimiter.class})
    static class Config {
        @Bean UserMapper users(){ return mock(UserMapper.class); }
        @Bean DeviceMapper devices(){ return mock(DeviceMapper.class); }
        @Bean MergeMapper merges(){ return mock(MergeMapper.class); }
        @Bean VerificationCodeService codes(){ return mock(VerificationCodeService.class); }
        @Bean PasswordEncoder encoder(){ return spy(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()); }
        @Bean JwtUtil jwt(){ return mock(JwtUtil.class); }
        @Bean org.springframework.data.redis.core.StringRedisTemplate redis(){ return mock(org.springframework.data.redis.core.StringRedisTemplate.class); }
    }
    @Test void deploymentRejectsRegistrationBeforeConsumingCodeOrWritingData() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).withPropertyValues("planlock.deployment.enabled=true").run(ctx->{
            var req=new RegisterRequest(); req.setAccount("new@example.org"); req.setCode("123456");
            assertThatThrownBy(()->ctx.getBean(AuthService.class).register(req)).isInstanceOf(BusinessException.class).hasMessageContaining("注册已关闭");
            verifyNoInteractions(ctx.getBean(VerificationCodeService.class), ctx.getBean(UserMapper.class), ctx.getBean(DeviceMapper.class));
        });
    }
    @Test void developmentKeepsExistingVerificationRegistration() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).run(ctx->{
            var req=new RegisterRequest(); req.setAccount("new@example.org"); req.setCode("bad");
            assertThatThrownBy(()->ctx.getBean(AuthService.class).register(req)).isInstanceOf(BusinessException.class).hasMessageContaining("验证码");
            verify(ctx.getBean(VerificationCodeService.class)).verify("new@example.org","bad");
        });
    }
    @Test void setupOwnerUsesExistingHashAndUserInsertWithoutBindingDeviceOrIssuingToken() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).run(ctx->{
            var users=ctx.getBean(UserMapper.class);
            doAnswer(call->{ ((com.planlock.account.entity.User)call.getArgument(0)).setId(42L); return 1; }).when(users).insert(any());
            assertThat(ctx.getBean(AuthService.class).createSetupOwner("owner@example.org","real-password")).isEqualTo(42L);
            var saved=org.mockito.ArgumentCaptor.forClass(com.planlock.account.entity.User.class);
            verify(users).insert(saved.capture());
            assertThat(saved.getValue().getEmail()).isEqualTo("owner@example.org");
            assertThat(ctx.getBean(PasswordEncoder.class).matches("real-password",saved.getValue().getPasswordHash())).isTrue();
            verifyNoInteractions(ctx.getBean(DeviceMapper.class),ctx.getBean(JwtUtil.class),ctx.getBean(VerificationCodeService.class));
        });
    }
    @Test void developmentRegistrationSharesAccountCreationAndStillBindsDevice() {
        new ApplicationContextRunner().withUserConfiguration(Config.class).run(ctx->{
            var req=new RegisterRequest(); req.setAccount("owner@example.org"); req.setCode("123456"); req.setPassword("real-password"); req.setDeviceUid("dev-device"); req.setPlatform("android");
            when(ctx.getBean(VerificationCodeService.class).verify("owner@example.org","123456")).thenReturn(true);
            doAnswer(call->{ ((com.planlock.account.entity.User)call.getArgument(0)).setId(42L); return 1; }).when(ctx.getBean(UserMapper.class)).insert(any());
            doAnswer(call->{ ((com.planlock.account.entity.Device)call.getArgument(0)).setId(7L); return 1; }).when(ctx.getBean(DeviceMapper.class)).insert(any());
            var result=ctx.getBean(AuthService.class).register(req);
            assertThat(result.getUserId()).isEqualTo(42L); assertThat(result.getDeviceId()).isEqualTo(7L);
            verify(ctx.getBean(JwtUtil.class)).generate(42L,7L);
        });
    }
}
