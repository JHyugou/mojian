package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 的一次运行记录。
 *
 * <p>为什么单独一张表：会话消息只保存"人话"（用户说了什么、最终回答），
 * 而一次运行真正的价值在过程 —— 调了哪些工具、几步、花了多少 token、用了多久、
 * 上下文装了什么。这些是排查"它为什么没查资料"和做评测时唯一的数据来源。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_run")
public class AgentRun extends BaseEntity {

    private Long projectId;

    private Long conversationId;

    /** PLANNER / WRITER */
    private String agentRole;

    private String model;

    /** FINISHED / MAX_STEPS / CANCELLED / FAILED */
    private String status;

    private Integer steps;

    private Integer promptTokens;

    private Integer cachedTokens;

    private Integer completionTokens;

    private Long latencyMs;

    /** 上下文装填摘要（装了什么、裁了什么、用掉多少） */
    private String contextSummary;

    /** 工具轨迹 JSON（每步的工具名/参数/结果摘要） */
    private String traceJson;

    private String errorMessage;
}
