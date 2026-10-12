package com.mojian.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 章节记忆：与章节一对一，保存记忆全文（FR-008，单大框形态）。
 * 摘要/重要事实/未解决冲突/人物状态变化 均可在大框内自然书写。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chapter_memory")
public class ChapterMemory extends BaseEntity {

    private Long chapterId;

    /** 记忆全文：AI 提炼与手工编辑共用同一字段 */
    private String memory;
}
