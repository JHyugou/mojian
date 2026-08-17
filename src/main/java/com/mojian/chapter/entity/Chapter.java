package com.mojian.chapter.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

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

    /** 状态：DRAFT / WRITING / COMPLETED */
    private String status;

    /** 当前版本号 */
    private Integer currentVersionNo;
}
