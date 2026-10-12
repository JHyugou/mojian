package com.mojian.generation.config;

import com.mojian.generation.llm.LlmClient;
import com.mojian.generation.llm.LlmProperties;
import com.mojian.generation.llm.RoutingLlmClient;
import com.mojian.settings.service.LlmSettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型客户端装配（FR-012 / FR-019）：
 * 生成链路统一注入 RoutingLlmClient，由它在"设置页配置 → 环境变量 → 演示模式"
 * 之间裁决，并在配置变化时热切换——本类不再固化启动期决策。
 */
@Configuration
public class LlmConfig {

    @Bean
    public LlmClient llmClient(LlmProperties properties, LlmSettingsService settingsService) {
        return new RoutingLlmClient(properties, settingsService);
    }
}
