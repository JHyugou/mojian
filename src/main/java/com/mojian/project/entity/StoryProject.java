package com.mojian.project.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("story_project")
public class StoryProject extends BaseEntity {

    private String name;

    private String intro;

    /** 题材，如：玄幻/都市/悬疑 */
    private String genre;

    /** 整体文风 */
    private String style;

    /** 项目级默认写作规则 */
    private String writingRules;
}
