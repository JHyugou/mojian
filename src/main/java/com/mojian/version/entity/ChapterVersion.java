package com.mojian.version.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 章节版本：不可变快照，只插入不更新（FR-016）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chapter_version")
public class ChapterVersion extends BaseEntity {

    private Long chapterId;

    private Integer versionNo;

    private String content;

    /** 来源生成任务 */
    private Long sourceTaskId;

    /** AI / MANUAL / ROLLBACK */
    private String source;

    private String remark;
}
