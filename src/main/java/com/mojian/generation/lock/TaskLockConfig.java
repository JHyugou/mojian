package com.mojian.generation.lock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 根据 mojian.redis.enabled 选择任务锁实现。
 * 默认 false：使用 NoopTaskLock，不依赖 Redis；
 * 设为 true：使用 RedisTaskLock，作为防重复提交的第一道快速拦截。
 */
@Configuration
public class TaskLockConfig {

    @Bean
    @ConditionalOnProperty(name = "mojian.redis.enabled", havingValue = "false", matchIfMissing = true)
    public TaskLock noopTaskLock() {
        return new NoopTaskLock();
    }

    @Bean
    @ConditionalOnProperty(name = "mojian.redis.enabled", havingValue = "true")
    public TaskLock redisTaskLock(StringRedisTemplate redisTemplate) {
        return new RedisTaskLock(redisTemplate);
    }
}
