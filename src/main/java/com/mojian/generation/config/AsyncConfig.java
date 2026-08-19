package com.mojian.generation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 生成任务线程池：与 Web 线程隔离，长耗时模型调用不阻塞 HTTP 请求。
 * 测试环境通过 @Primary 同步 Executor 覆盖，保证断言确定性。
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean("generationExecutor")
    public Executor generationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("gen-");
        executor.initialize();
        return executor;
    }
}
