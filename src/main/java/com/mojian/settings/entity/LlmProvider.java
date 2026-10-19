package com.mojian.settings.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户配置的一个模型服务商。
 *
 * <p>与旧的单例 llm_settings 不同：这里可以同时存在多个服务商，
 * 每个都有自己的地址和 Key，模型库按服务商聚合，Agent 选择 provider + model。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("llm_provider")
public class LlmProvider extends BaseEntity {

    /** 展示名，例如 DeepSeek 官方、OpenCode Go */
    private String name;

    /** 内置预设 id；自定义为空 */
    private String presetKey;

    private String baseUrl;

    /** AES-GCM 密文 */
    private String apiKeyCipher;

    /** 该服务商拉取到的模型列表（JSON 数组，LlmModelVO） */
    private String modelsJson;

    private Boolean enabled;
}
