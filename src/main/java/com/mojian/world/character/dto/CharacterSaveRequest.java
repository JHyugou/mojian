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

    private String appearance;

    private String personality;

    private String coreGoal;

    private String mainConflict;

    private String relationships;

    private String speechStyle;

    private String exampleDialogue;

    private String currentStatus;

    private String characterArc;

    /** 是否启用，缺省为 true */
    private Boolean enabled;
}
