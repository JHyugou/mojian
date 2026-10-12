package com.mojian.world.character.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CharacterSaveRequest {

    @NotBlank(message = "人物姓名不能为空")
    @Size(max = 50, message = "人物姓名不能超过50字")
    private String name;

    @Size(max = 200, message = "身份定位不能超过200字")
    private String roleIdentity;

    /** 人物设定全文：外貌/性格/能力/关系/状态融合的大段自然语言描述 */
    private String description;

    private String exampleDialogue;

    /** 是否启用，缺省为 true */
    private Boolean enabled;
}
