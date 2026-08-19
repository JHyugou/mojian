package com.mojian.generation.lock;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Redis 任务锁：setnx 快速拦截 + 超时自动释放，避免服务异常时锁永久占用。
 * 即使 Redis 不可用，数据库唯一约束仍是最终兜底，不会丢失防重复能力。
 */
@RequiredArgsConstructor
public class RedisTaskLock implements TaskLock {

    private static final Duration LOCK_TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean tryLock(String key, String token) {
        Boolean ok = redisTemplate.opsForValue().setIfAbsent(key, token, LOCK_TTL);
        return Boolean.TRUE.equals(ok);
    }

    @Override
    public void unlock(String key, String token) {
        String current = redisTemplate.opsForValue().get(key);
        if (token.equals(current)) {
            redisTemplate.delete(key);
        }
    }
}
