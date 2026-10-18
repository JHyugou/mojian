package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 策划 Agent 发起的人物/设定/蓝图删除申请。
 *
 * <p>删除不可逆，不能只靠提示词约束模型：工具只负责登记申请，
 * 必须由作者在界面上确认后才真正删除。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_delete_request")
public class AgentDeleteRequest extends BaseEntity {

    private Long projectId;

    private Long conversationId;

    /** CHARACTER / SETTING / OUTLINE */
    private String entityType;

    private Long entityId;

    /** 申请时的名称快照，删除后仍能看出删了什么 */
    private String entityName;

    private String reason;

    /** PENDING / APPROVED / REJECTED */
    private String status;

    /** PLANNER */
    private String source;
}
