package com.planlock.app.setup;

import com.planlock.account.dto.*;
import com.planlock.account.entity.*;
import com.planlock.account.mapper.*;
import com.planlock.account.service.AuthService;
import com.planlock.common.BusinessException;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.context.request.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentAuthLimitTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(RegistrationPolicyTest.Config.class)
            .withPropertyValues("planlock.deployment.enabled=true");

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }
    private void source(String address) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(address);
        request.addHeader("X-Forwarded-For", UUID.randomUUID().toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @SuppressWarnings("unchecked")
    private Map<String, Long> storage(StringRedisTemplate redis) {
        Map<String, Long> counts = new java.util.concurrent.ConcurrentHashMap<>();
        when(redis.execute(any(RedisScript.class), anyList())).thenAnswer(call -> {
            RedisScript<Long> script = call.getArgument(0); List<String> keys = call.getArgument(1);
            synchronized (counts) {
                if (script.getScriptAsString().contains("INCR")) {
                    if (script.getScriptAsString().contains("return -1") && keys.stream().anyMatch(key -> counts.getOrDefault(key, 0L) >= 5)) return -1L;
                    keys.forEach(key -> counts.merge(key, 1L, Long::sum));
                    if (script.getScriptAsString().contains("return -1")) return 0L;
                }
                return keys.stream().mapToLong(key -> counts.getOrDefault(key, 0L)).max().orElse(0);
            }
        });
        when(redis.delete(anyCollection())).thenAnswer(call -> {
            ((Collection<String>) call.getArgument(0)).forEach(counts::remove); return 1L;
        });
        return counts;
    }
    private LoginRequest login(String account, String password) {
        var req = new LoginRequest(); req.setAccount(account); req.setPassword(password);
        req.setDeviceUid("device"); req.setPlatform("android"); return req;
    }
    private String sourceKey(org.springframework.context.ApplicationContext ctx, String remote, String forwarded) {
        var request = new MockHttpServletRequest(); request.setRemoteAddr(remote);
        if (forwarded != null) request.addHeader("X-Forwarded-For", forwarded);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return ctx.getBean(com.planlock.account.service.DeploymentAuthLimiter.class).sourceKey();
    }
    @Test void explicitProxyUsesNearestUntrustedHopAndIgnoresForgedPrefix() {
        runner.withPropertyValues("planlock.deployment.trusted-proxies=127.0.0.1,::1").run(ctx -> {
            String expected = sourceKey(ctx, "192.0.2.8", null);
            assertThat(sourceKey(ctx, "127.0.0.1", "203.0.113.99, 192.0.2.8, ::1")).isEqualTo(expected);
            assertThat(sourceKey(ctx, "127.0.0.1", "203.0.113.42, 192.0.2.8, ::1")).isEqualTo(expected);
            assertThat(sourceKey(ctx, "192.0.2.8", "203.0.113.99")).isEqualTo(expected);
        });
    }
    @Test void noConfiguredProxyKeepsRemoteAddressEvenForLoopback() {
        runner.run(ctx -> assertThat(sourceKey(ctx,"127.0.0.1","192.0.2.8"))
                .isEqualTo(sourceKey(ctx,"127.0.0.1",null)));
    }
    @Test void malformedOrOversizedForwardedChainFallsBackWithoutDnsLookup() {
        runner.withPropertyValues("planlock.deployment.trusted-proxies=127.0.0.1").run(ctx -> {
            String expected = sourceKey(ctx,"127.0.0.1",null);
            for (String header : List.of("", "192.0.2.8,", "attacker.example", "1.2.3", "999.1.1.1", "fe80::1%eth0", "a".repeat(4097))) {
                assertThat(sourceKey(ctx,"127.0.0.1",header)).as(header.substring(0,Math.min(32,header.length()))).isEqualTo(expected);
            }
        });
    }
    @Test void ipv6SourceHasOneBudgetAcrossCanonicalSpellings() {
        runner.withPropertyValues("planlock.deployment.trusted-proxies=::1").run(ctx -> {
            assertThat(sourceKey(ctx,"::1","2001:db8::8")).isEqualTo(sourceKey(ctx,"2001:db8:0:0:0:0:0:8",null));
        });
    }
    private User owner(PasswordEncoder encoder) {
        var user = new User(); user.setId(42L); user.setEmail("owner@example.org");
        user.setPasswordHash(encoder.encode("valid-password"));
        user.setSecurityAnswerHash(encoder.encode("valid-answer")); return user;
    }
    @Test void accountLimitSurvivesSourceAndAccountSpellingChanges() {
        runner.run(ctx -> {
            storage(ctx.getBean(StringRedisTemplate.class));
            var users = ctx.getBean(UserMapper.class); var service = ctx.getBean(AuthService.class);
            var owner = owner(ctx.getBean(PasswordEncoder.class));
            when(users.findByAccount(anyString())).thenReturn(owner);
            for (int i = 0; i < 5; i++) {
                source("192.0.2." + i);
                assertThatThrownBy(() -> service.login(login("owner@example.org", "wrong"))).hasMessage("账号或密码错误");
            }
            source("192.0.2.100");
            assertThatThrownBy(() -> service.login(login("OWNER@example.org ", "valid-password")))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("15 分钟");
            verify(ctx.getBean(PasswordEncoder.class), never()).matches(eq("valid-password"), anyString());
            verifyNoInteractions(ctx.getBean(DeviceMapper.class));
        });
    }
    @Test void sourceLimitSurvivesAccountAndForwardedHeaderChanges() {
        runner.run(ctx -> {
            storage(ctx.getBean(StringRedisTemplate.class)); var service = ctx.getBean(AuthService.class);
            for (int i = 0; i < 5; i++) {
                source("192.0.2.1"); final int index = i;
                assertThatThrownBy(() -> service.login(login("unknown" + index, "wrong"))).hasMessage("账号或密码错误");
            }
            clearInvocations(ctx.getBean(UserMapper.class)); source("192.0.2.1");
            assertThatThrownBy(() -> service.login(login("another", "wrong"))).hasMessageContaining("15 分钟");
            verifyNoInteractions(ctx.getBean(UserMapper.class));
        });
    }
    @Test void securityAnswerFailuresSharePasswordLimit() {
        runner.run(ctx -> {
            storage(ctx.getBean(StringRedisTemplate.class)); var service = ctx.getBean(AuthService.class);
            var owner = owner(ctx.getBean(PasswordEncoder.class));
            when(ctx.getBean(UserMapper.class).findByAccount(anyString())).thenReturn(owner);
            source("192.0.2.1");
            for (int i = 0; i < 5; i++) {
                var req = new BackupPasswordResetRequest(); req.setAccount("owner@example.org"); req.setSecurityAnswer("wrong");
                assertThatThrownBy(() -> service.resetBackupPassword(req)).hasMessage("账号或密码错误");
            }
            assertThatThrownBy(() -> service.login(login("owner@example.org", "valid-password"))).hasMessageContaining("15 分钟");
            verify(ctx.getBean(UserMapper.class), never()).updateBackupPassword(any(), any(), any(), any());
        });
    }
    @Test void successfulLoginClearsBothCounters() {
        runner.run(ctx -> {
            var counts = storage(ctx.getBean(StringRedisTemplate.class)); var service = ctx.getBean(AuthService.class);
            var owner = owner(ctx.getBean(PasswordEncoder.class));
            when(ctx.getBean(UserMapper.class).findByAccount(anyString())).thenReturn(owner);
            doAnswer(call -> { ((Device) call.getArgument(0)).setId(7L); return 1; }).when(ctx.getBean(DeviceMapper.class)).insert(any());
            source("192.0.2.1");
            assertThatThrownBy(() -> service.login(login("owner@example.org", "wrong"))).hasMessage("账号或密码错误");
            assertThat(counts).isNotEmpty();
            assertThat(service.login(login("owner@example.org", "valid-password")).getUserId()).isEqualTo(42L);
            assertThat(counts).isEmpty();
        });
    }
    @Test void redisFailureDoesNotFallBackToPasswordAuthentication() {
        runner.run(ctx -> {
            when(ctx.getBean(StringRedisTemplate.class).execute(any(RedisScript.class), anyList()))
                    .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("unavailable"));
            source("192.0.2.1");
            assertThatThrownBy(() -> ctx.getBean(AuthService.class).login(login("owner@example.org", "valid-password")))
                    .isInstanceOf(org.springframework.data.redis.RedisConnectionFailureException.class);
            verifyNoInteractions(ctx.getBean(UserMapper.class), ctx.getBean(DeviceMapper.class));
        });
    }

    @Test void concurrentBurstCannotCompareMoreThanFivePasswords() {
        runner.run(ctx -> {
            storage(ctx.getBean(StringRedisTemplate.class));
            var encoder = ctx.getBean(PasswordEncoder.class);
            var owner = owner(encoder);
            when(ctx.getBean(UserMapper.class).findByAccount(anyString())).thenReturn(owner);
            var compared = new java.util.concurrent.atomic.AtomicInteger();
            doAnswer(call -> { compared.incrementAndGet(); Thread.sleep(300); return false; })
                    .when(encoder).matches(anyString(), anyString());
            var ready = new java.util.concurrent.CountDownLatch(20);
            var start = new java.util.concurrent.CountDownLatch(1);
            try (var workers = java.util.concurrent.Executors.newFixedThreadPool(20)) {
                java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < 20; i++) futures.add(workers.submit(() -> {
                    source("192.0.2.1"); ready.countDown();
                    try {
                        start.await();
                        assertThatThrownBy(() -> ctx.getBean(AuthService.class).login(login("owner@example.org", "wrong")))
                                .isInstanceOf(BusinessException.class);
                    } catch (InterruptedException e) { throw new RuntimeException(e); }
                    finally { RequestContextHolder.resetRequestAttributes(); }
                }));
                assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); start.countDown();
                for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
            assertThat(compared.get()).isEqualTo(5);
        });
    }
}
