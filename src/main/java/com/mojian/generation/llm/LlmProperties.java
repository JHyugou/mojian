package com.mojian.generation.llm;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型服务配置（全部来自服务端环境变量，SRS 12.2）。
 * api-key 为空或 mock=true 时自动使用 MockLlmClient。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mojian.llm")
public class LlmProperties {

    private String baseUrl;

    private String apiKey;

    private String model;

    /** 显式启用 Mock（演示/测试），或未配置 api-key 时隐式启用 */
    private boolean mock;

    /** 连接超时（秒），SRS 7.3 */
    private int connectTimeoutSeconds = 10;

    /** 读取/空闲超时（秒），SRS 7.3 */
    private int readTimeoutSeconds = 300;
}
