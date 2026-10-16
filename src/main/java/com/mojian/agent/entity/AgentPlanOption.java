package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 主 Agent 给出的剧情走向方案。
 *
 * <p>方案卡是交互区最核心的交互：同一个岔路口上的几种不同取向，
 * 用户选一条、或写自己的修改意见。所以必须结构化存储 ——
 * 存在数据库里才能渲染成卡片、才能记住"用户当时选了哪条"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_plan_option")
public class AgentPlanOption extends BaseEntity {

    private Long conversationId;

    /** 产出这批方案的那条 assistant 消息 */
    private Long messageId;

    /** 第几个方案，从 1 开始 */
    private Integer optionIndex;

    private String title;

    /** 一句话简介，卡片正面显示 */
    private String summary;

    /** 详细走向，展开显示 */
    private String detail;

    private Boolean selected;
}
