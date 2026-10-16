package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 模型调用用量流水 —— 让花销可见。
 *
 * <p>额度是硬上限，用完服务直接不可用。没有流水，"这个月花了多少、哪次调用最贵"
 * 就只能靠猜。有了它：界面上能显示实时消费，开发期也能确认成本没失控。
 *
 * <p>这也是"我不能口头保证成本，但可以把它变成可测量"的具体落地。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_usage_log")
public class AgentUsageLog extends BaseEntity {

    private Long projectId;

    private Long conversationId;

    /** PLANNER / WRITER */
    private String agentRole;

    private String model;

    private Integer promptTokens;

    /** 命中 prompt 缓存的部分（单价通常只有未命中的几十分之一） */
    private Integer cachedTokens;

    private Integer completionTokens;

    /** 估算费用（美元）。单价来自 {@code ModelPricing}，是估算不是账单 */
    private BigDecimal estimatedCost;
}
