package com.mojian.chapter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChapterSaveRequest {

    @NotBlank(message = "章节标题不能为空")
    @Size(max = 200, message = "章节标题不能超过200字")
    private String title;

    /** 关联的蓝图章节节点 */
    private Long outlineNodeId;

    /** 状态：DRAFT / WRITING / COMPLETED，缺省 DRAFT */
    @Pattern(regexp = "^$|DRAFT|WRITING|COMPLETED", message = "章节状态不合法")
    private String status;

    /** 正文（手动保存，FR-007） */
    private String content;

    /** 手动保存时是否同时创建版本快照（FR-016，缺省 false） */
    private Boolean createVersion;

    /** 版本备注（createVersion=true 时生效） */
    @Size(max = 200, message = "版本备注不能超过200字")
    private String remark;
}
