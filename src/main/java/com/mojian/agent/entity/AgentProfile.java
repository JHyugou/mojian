package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 角色配置（固定两行：id=1 策划、id=2 写作）。
 *
 * <p>把"用哪个模型"按角色拆开，是因为两个 Agent 的诉求天然不同：
 * 策划要稳、要便宜、要能一口气读完几十万字设定；写作要发散、要文笔。
 * 同一个模型服务两边是妥协，不是设计。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_profile")
public class AgentProfile extends BaseEntity {

    /** PLANNER（策划）/ WRITER（写作） */
    private String role;

    private String model;

    /** 策划宜低（求稳），写作宜高（求发散） */
    private java.math.BigDecimal temperature;

    /**
     * 思考强度。
     *
     * <p>值域各家不同（{@code none/low/medium/high} 或 {@code xhigh/max}），
     * 所以这里只存字符串、不在库里约束 —— 可选值由模型目录提供。
     * 为空表示不传这个字段，由服务商用自己的默认值。
     */
    private String reasoningEffort;

    private Integer maxSteps;

    /**
     * 模型的上下文上限。默认给保守值，被服务端超限错误纠正后自动上调
     * —— 见 {@code com.mojian.agent.context.ContextLimitParser}。
     */
    private Integer contextLimit;

    /** 预留给输出 + 系统提示词 + 工具清单的 token */
    private Integer reserveOutputTokens;

    /** 留空表示复用 llm_settings 的接口地址 */
    private String baseUrl;

    /** 留空表示复用 llm_settings 的密钥（AES-GCM 密文） */
    private String apiKeyCipher;

    private Boolean enabled;
}
