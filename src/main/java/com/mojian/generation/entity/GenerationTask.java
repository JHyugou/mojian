package com.mojian.generation.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 生成任务（FR-014）。active_slot 为数据库生成列，实体不映射，
 * 由唯一索引 (chapter_id, active_slot) 保证同章节最多一个活动任务。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("generation_task")
public class GenerationTask extends BaseEntity {

    private Long chapterId;

    /** PLOT_PLAN / CONTENT / CONTINUE / REWRITE / EXPAND_SHRINK / CHAPTER_MEMORY */
    private String taskType;

    /** CREATED / RUNNING / SUCCEEDED / FAILED / CANCELLED */
    private String status;

    /** 输入快照（JSON，不含 API Key） */
    private String inputSnapshot;

    /** 上下文快照（JSON，不含 API Key） */
    private String contextSnapshot;

    /** 结果快照（剧情方案文本等）。置空需写库，故用 IGNORED 策略覆盖默认 NOT_NULL */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String resultSnapshot;

    /** 失败原因。重试成功后必须清空残留错误信息 */
    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private String errorMessage;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private LocalDateTime startedAt;

    @TableField(updateStrategy = FieldStrategy.IGNORED)
    private LocalDateTime finishedAt;
}
