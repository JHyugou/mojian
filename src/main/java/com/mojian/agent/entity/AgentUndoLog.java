package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 一份"可撤销的数据库改动"。
 *
 * <p>对话回滚如果只删消息，书里的人物/设定/蓝图却留在原处，用户会看到
 * "对话回去了、数据没回去"的分裂状态。这里记录 Agent 草稿被确认时对业务表的
 * 实际改动；回滚时按相反操作撤销。
 *
 * <p>只记录策划 Agent 经草稿闸门写入的结构性数据（人物/设定/蓝图）；
 * 正文有独立的版本机制，不走这张表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_undo_log")
public class AgentUndoLog extends BaseEntity {

    private Long projectId;

    private Long conversationId;

    /** 改动发生时，该会话里已有多少条 user 消息 */
    private Integer turnIndex;

    /** CREATED / UPDATED */
    private String actionType;

    /** CHARACTER / SETTING / OUTLINE / CHAPTER */
    private String entityType;

    private Long entityId;

    /** 被确认的草稿 id：撤销后把它退回待确认 */
    private Long draftId;

    /** UPDATED 时的旧值（JSON；CREATED 为空） */
    private String beforeJson;
}
