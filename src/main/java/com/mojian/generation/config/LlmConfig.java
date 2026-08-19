package com.mojian.generation.config;

import com.mojian.generation.llm.LlmClient;
import com.mojian.generation.llm.LlmProperties;
import com.mojian.generation.llm.MockLlmClient;
import com.mojian.generation.llm.OpenAiCompatLlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 模型客户端装配（FR-012）：
 * 显式 mock 或未配置 api-key 时使用 MockLlmClient，否则接入 OpenAI 兼容服务。
 */
@Slf4j
@Configuration
public class LlmConfig {

    @Bean
    public LlmClient llmClient(LlmProperties properties) {
        if (properties.isMock() || !StringUtils.hasText(properties.getApiKey())) {
            log.info("LLM 客户端: MockLlmClient（演示/测试模式）");
            return new MockLlmClient();
        }
        log.info("LLM 客户端: OpenAiCompatLlmClient（模型: {}）", properties.getModel());
        return new OpenAiCompatLlmClient(properties);
    }
}
