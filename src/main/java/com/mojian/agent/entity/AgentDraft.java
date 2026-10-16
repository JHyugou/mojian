package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Agent 起草的变更草案 —— <b>人工确认机制的载体</b>。
 *
 * <p>为什么需要这张表，而不是让工具直接写业务表：
 * Agent 的循环是"一口气跑完"的，没有暂停/恢复机制，所以"等用户点确认再执行"
 * 不能靠循环停在那里等。正确做法是让工具把产出写进这张<b>独立</b>的草稿表，
 * 用户在前端点确认后，才由确认接口把它写进 character_profile / setting_entry / plot_outline。
 *
 * <p>好处有三：
 * <ol>
 *   <li><b>没确认的东西绝不污染业务数据</b> —— 这是最重要的一条</li>
 *   <li>Agent 不用暂停，循环照常结束</li>
 *   <li>每次 Agent 的产出都留痕，可以回看"它当时想加什么、为什么"</li>
 * </ol>
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent_draft")
public class AgentDraft extends BaseEntity {

    private Long conversationId;

    private Long projectId;

    /** CHARACTER / SETTING / BLUEPRINT */
    private String draftType;

    /** PENDING / ACCEPTED / REJECTED */
    private String status;

    /** 展示用标题（人物名 / 设定名 / 蓝图节点名） */
    private String title;

    /** 草稿内容（JSON）。不同类型字段不同，所以用 JSON 而不是一堆列 */
    private String payloadJson;

    /** Agent 为什么起草它 —— 可追溯，也是"双 Agent 信息损耗"的一种补偿 */
    private String agentReason;

    /** 确认后写入的业务记录 id，便于从草稿跳到正式数据 */
    private Long appliedId;
}
