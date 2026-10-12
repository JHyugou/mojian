package com.mojian.outline.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OutlineNodeSaveRequest {

    @NotNull(message = "节点类型不能为空")
    @Pattern(regexp = "VOLUME|ARC|CHAPTER", message = "节点类型必须是 VOLUME/ARC/CHAPTER")
    private String nodeType;

    /** 父节点ID，卷节点为空 */
    private Long parentId;

    @NotBlank(message = "节点名称不能为空")
    @Size(max = 100, message = "节点名称不能超过100字")
    private String name;

    private String summary;

    /** 章节节点：主要人物档案ID（JSON数组字符串） */
    private String mainCharacters;

    /** 同级排序，缺省 0 */
    private Integer sortOrder;

    @Pattern(regexp = "^$|PLANNED|WRITING|DONE", message = "节点状态不合法")
    private String status;
}
