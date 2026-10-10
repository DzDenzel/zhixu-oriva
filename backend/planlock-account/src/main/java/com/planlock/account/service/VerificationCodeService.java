package com.planlock.account.service;

import com.planlock.common.BusinessException;
import com.planlock.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 验证码服务：Redis 存储（TTL 5 分钟 + 60 秒发送限频，key 见 04-技术实现/数据库设计.md）。
 * 开发期验证码仅日志输出，生产接短信/邮件网关（见 接口设计 v0.1.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationCodeService {

    private static final String KEY_PREFIX = "verify:";
    private static final String LIMIT_PREFIX = "verify:limit:";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration LIMIT_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final SecureRandom random = new SecureRandom();

    public String issue(String account) {
        String limitKey = LIMIT_PREFIX + account;
        if (Boolean.TRUE.equals(redis.hasKey(limitKey))) {
            throw new BusinessException(ErrorCode.INVALID_PARAM, "发送过于频繁，请稍后再试");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        redis.opsForValue().set(KEY_PREFIX + account, code, TTL);
        redis.opsForValue().set(limitKey, "1", LIMIT_TTL);
        log.info("[DEV] verification code issued for account {}: {}", account, code);
        return code;
    }

    public boolean verify(String account, String code) {
        if (account == null || code == null) {
            return false;
        }
        String stored = redis.opsForValue().get(KEY_PREFIX + account);
        return stored != null && stored.equals(code);
    }
}
