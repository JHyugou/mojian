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
 *
 * <p>三个内容字段对应协议里的三种消息形态，重建上下文时一一还原：
 * <ul>
 *   <li>{@code content} —— system / user / assistant 的文本；tool 行放工具返回的结果</li>
 *   <li>{@code toolCallId} —— 仅 tool 行，模型靠它对上"这是哪次调用的结果"</li>
 *   <li>{@code toolCallsJson} —— 仅 assistant 行，本轮要求的（可能多个）工具调用</li>
 * </ul>
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

    /** JSON 数组。一轮可以要求调多个工具，所以不能按"一个工具一列"存 */
    private String toolCallsJson;
}
