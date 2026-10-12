package com.mojian.world.character.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人物版本节点保存请求（起点制）。
 * 文本字段为 null 表示沿用基础档案，空字符串表示有意清空。
 */
@Data
public class CharacterNodeSaveRequest {

    /** 生效起点章（含）。第 1 章固定使用基础设定，节点需从第 2 章起 */
    @NotNull(message = "生效起点章节不能为空")
    @Min(value = 2, message = "版本节点需从第 2 章起生效（第 1 章使用基础设定）")
    private Integer chapterFrom;

    /** 本阶段人物设定全文快照（null = 沿用基础档案，空字符串 = 有意清空） */
    private String description;
}
