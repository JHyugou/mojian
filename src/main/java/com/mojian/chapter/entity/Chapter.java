package com.mojian.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chapter")
public class Chapter extends BaseEntity {

    private Long projectId;

    /** 关联的蓝图章节节点 */
    private Long outlineNodeId;

    private String title;

    /** 当前工作正文 */
    private String content;

    /**
     * 本章写作要求：除了"写什么"（蓝图），还要能表达"怎么写"
     * —— 风格、人称、篇幅、禁忌、必须出现或必须避开的桥段。
     * 由写作 Agent 作为必需上下文注入，永不裁剪。
     */
    private String writingRequirement;

    /** 写作要求执行状态：NONE 无要求 / PENDING 待执行 / EXECUTED 已执行 */
    private String writingRequirementStatus;

    /** 最近一次按写作要求完成写作的时间 */
    private LocalDateTime writingRequirementExecutedAt;

    /** 状态：DRAFT / WRITING / COMPLETED */
    private String status;

    /** 当前版本号 */
    private Integer currentVersionNo;
}
