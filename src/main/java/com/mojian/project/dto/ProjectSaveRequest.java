package com.mojian.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 项目创建/更新请求。PUT 为全量替换语义。
 */
@Data
public class ProjectSaveRequest {

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 100, message = "项目名称不能超过100字")
    private String name;

    @Size(max = 1000, message = "简介不能超过1000字")
    private String intro;

    @Size(max = 50, message = "题材不能超过50字")
    private String genre;

    @Size(max = 200, message = "整体文风不能超过200字")
    private String style;

    private String writingRules;
}
