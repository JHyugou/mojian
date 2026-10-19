package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 策划 Agent 下发给写作 Agent 的一条写作指令。
 *
 * <p>它不是用户消息：来源是策划 Agent，但由写作 Agent 在执行时读取。
 * 保留 PENDING/CONSUMED 状态，用户才能在写作面板看见"策划说了什么、执行了没有"。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("writer_instruction")
public class WriterInstruction extends BaseEntity {

    private Long projectId;

    private Long chapterId;

    /** 策划 Agent 写的、给写作 Agent 的提示词 */
    private String content;

    /** 为什么下这条指令（给用户看的上下文） */
    private String reason;

    /** 来源：PLANNER */
    private String source;

    /** PENDING / CONSUMED / CANCELLED */
    private String status;
}
