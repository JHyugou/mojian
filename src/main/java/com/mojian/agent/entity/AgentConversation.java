package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一个 Agent 会话。
 *
 * <p>策划会话是"聊出来的一部长篇框架"，写作会话是"某一章的写作过程"，
 * 所以两者分开存，靠 {@link #agentRole} 区分。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_conversation")
public class AgentConversation extends BaseEntity {

    private Long projectId;

    /** PLANNER / WRITER */
    private String agentRole;

    /** 会话标题，取首条用户消息前若干字 */
    private String title;
}
