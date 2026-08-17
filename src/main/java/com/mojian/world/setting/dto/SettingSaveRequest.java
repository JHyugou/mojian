package com.mojian.world.setting.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SettingSaveRequest {

    @NotBlank(message = "条目名称不能为空")
    @Size(max = 100, message = "条目名称不能超过100字")
    private String title;

    @NotBlank(message = "设定内容不能为空")
    private String content;

    @Size(max = 500, message = "标签不能超过500字")
    private String tags;

    @Size(max = 500, message = "触发关键词不能超过500字")
    private String triggerKeywords;

    /** 优先级，缺省 0 */
    private Integer priority;

    /** 适用章节范围，如 "3-30" 或 "1,5,9-12"；留空表示全部章节 */
    @Pattern(regexp = "^$|^\\d+(-\\d+)?([,，]\\d+(-\\d+)?)*$",
            message = "章节范围格式不正确，示例：3-30 或 1,5,9-12")
    private String chapterScope;

    /** 是否启用，缺省 true */
    private Boolean enabled;
}
