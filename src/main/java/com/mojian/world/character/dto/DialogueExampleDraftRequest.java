package com.mojian.world.character.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 对话示例 AI 草稿请求（FR-018）。
 */
@Data
public class DialogueExampleDraftRequest {

    /** 可选补充要求，如"对话要体现他的口头禅" */
    @Size(max = 200, message = "补充要求不能超过200字")
    private String hint;
}
