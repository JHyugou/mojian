package com.mojian.world.character.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("character_profile")
public class CharacterProfile extends BaseEntity {

    private Long projectId;

    private String name;

    /** 身份/角色定位 */
    private String roleIdentity;

    /** 人物设定全文（SillyTavern 式大段描述：外貌/性格/能力/关系/状态融合叙述） */
    private String description;

    /** 对话示例：表现人物声口最有效的素材 */
    private String exampleDialogue;

    /** 启用状态：只有启用的人物档案才参与默认上下文组装（FR-003） */
    private Boolean enabled;
}
