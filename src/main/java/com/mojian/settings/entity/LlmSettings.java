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

    /**
     * 用户启用的模型 id（JSON 数组）。
     *
     * <p>一个网关往往能提供几十上百个模型（OpenCode Go 目录里有 32 个），
     * 但没人会在下拉里翻三十项挑一个 —— 所以要能"关掉用不上的"。
     *
     * <p>为空/null 表示<b>全部启用</b>：让人先看见全部、再一个个关掉，
     * 比一开始给他一片空白要好得多。
     */
    private String enabledModels;

    private Boolean enabled;
}
