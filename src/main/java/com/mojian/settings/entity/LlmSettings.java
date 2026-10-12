package com.mojian.settings.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模型接入设置（单例行，id 固定为 1）：
 * 用户在界面配置的 OpenAI 兼容接口信息，优先级高于服务端环境变量；
 * api_key 只存密文，明文仅在调用模型的内存瞬间存在。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("llm_settings")
public class LlmSettings extends BaseEntity {

    private String baseUrl;

    /** AES-GCM 密文，本机密钥文件加密 */
    private String apiKeyCipher;

    private String model;

    private Boolean enabled;
}
