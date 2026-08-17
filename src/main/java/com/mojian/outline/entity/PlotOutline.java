package com.mojian.outline.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("plot_outline")
public class PlotOutline extends BaseEntity {

    private Long projectId;

    /** 父节点ID，NULL 为根节点（卷） */
    private Long parentId;

    /** 节点类型：VOLUME / ARC / CHAPTER */
    private String nodeType;

    private String name;

    private String summary;

    private String plotGoal;

    /** 章节节点：主要人物档案ID（JSON数组字符串） */
    private String mainCharacters;

    /** 章节节点：冲突 */
    private String conflict;

    /** 章节节点：场景 */
    private String scene;

    /** 章节节点：必须发生的情节 */
    private String mustEvents;

    private Integer sortOrder;

    /** 状态：PLANNED / WRITING / DONE */
    private String status;
}
