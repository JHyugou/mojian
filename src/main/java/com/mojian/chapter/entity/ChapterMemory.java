package com.mojian.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 章节记忆：与章节一对一，保存摘要、重要事实、未解决冲突和人物状态变化（FR-008）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chapter_memory")
public class ChapterMemory extends BaseEntity {

    private Long chapterId;

    private String summary;

    private String keyFacts;

    private String unresolvedConflicts;

    private String characterChanges;
}
