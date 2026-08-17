package com.mojian.world.setting.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("setting_entry")
public class SettingEntry extends BaseEntity {

    private Long projectId;

    private String title;

    private String content;

    /** 标签（逗号分隔） */
    private String tags;

    /** 触发关键词（逗号分隔），用于上下文匹配（FR-005） */
    private String triggerKeywords;

    /** 优先级：数值越大越优先注入 */
    private Integer priority;

    /** 适用章节范围，如 "3-30"；NULL 表示全部章节 */
    private String chapterScope;

    private Boolean enabled;
}
