package com.mojian.agent.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 策划审稿后追加给写作 Agent 的一条补充要求。
 *
 * <p>与章节的 {@code writingRequirement} 严格分开：
 * writingRequirement 是"这一章怎么写"的长期设定；补充要求是审稿后针对某处问题的追加修正。
 * 每条补充要求独立成行，不合并、不互相覆盖；写作任务下次执行时全部注入，
 * 成功写入正文后标记为 APPLIED（保留历史，策划再次审稿时能看到处理过什么）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chapter_supplement")
public class ChapterSupplement extends BaseEntity {

    private Long projectId;

    private Long chapterId;

    /** 具体的修改要求 */
    private String content;

    /** 为什么提这条要求（针对什么问题） */
    private String reason;

    /** 来源：PLANNER */
    private String source;

    /** PENDING 待执行 / EXECUTING 执行中 / SUBMITTED 已写入待核验 / APPLIED 已核验 */
    private String status;

    /** 正在执行这条补充要求的写作任务 id（仅 EXECUTING 时有值） */
    private Long executingTaskId;
}
