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

    private String appearance;

    private String personality;

    private String coreGoal;

    private String mainConflict;

    /** 人物关系（自由文本或JSON） */
    private String relationships;

    private String speechStyle;

    private String currentStatus;

    /** 人物发展弧线 */
    private String characterArc;

    /** 启用状态：只有启用的人物档案才参与默认上下文组装（FR-003） */
    private Boolean enabled;
}
