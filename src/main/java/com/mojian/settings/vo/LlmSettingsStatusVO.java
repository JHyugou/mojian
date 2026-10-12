package com.mojian.settings.vo;

import lombok.Data;

/**
 * 模型接入状态（设置页展示用）。
 * 安全约束：apiKey 只允许出现掩码形式，完整密钥永远不出后端。
 */
@Data
public class LlmSettingsStatusVO {

    /** LIVE = 已接入真模型；DEMO = 内置模拟模式 */
    private String mode;

    /** 生效来源：DB = 设置页配置；ENV = 服务端环境变量；DEMO 时为 null */
    private String source;

    private String baseUrl;

    private String model;

    /** 掩码形式，如 sk-••••abcd；未配置为 null */
    private String apiKeyMasked;
}
