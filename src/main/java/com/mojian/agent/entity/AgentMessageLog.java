package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 会话消息的持久化 —— 那个"一轮轮长大的包裹"存在数据库里的样子。
 *
 * <p>为什么不留在内存：写作任务跨请求、可能持续几分钟，服务还可能重启；
 * 而且用户要能翻回历史对话，看 Agent 当时查了什么资料、怎么想的。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_message_log")
public class AgentMessageLog extends BaseEntity {

    private Long conversationId;

    /** 第几轮，同一步内产生的多条消息共享同一个值 */
    private Integer stepNo;

    /** system / user / assistant / tool */
    private String role;

    private String content;

    /** 工具结果必须带 tool_call_id，模型才对得上这是哪次调用的结果 */
    private String toolCallId;

    private String toolName;

    private String toolArguments;

    private String toolResult;
}
