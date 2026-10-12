package com.mojian.world.character.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.mojian.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 人物版本节点（起点制增量补充，FR-003 演进版）：
 * 节点是基础设定的"变化补充"——从第 chapter_from 章起，角色发生了什么变化
 * （心境/能力/关系/处境…），生成本章时按起点升序全部追加在基础设定之后；
 * 生成早于起点的章节不注入该节点，不剧透后期演变。
 *
 * 姓名与启用开关是人物级属性，不随节点变化。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("character_node")
public class CharacterNode extends BaseEntity {

    private Long characterId;

    /** 生效起点章（含） */
    private Integer chapterFrom;

    /** 本阶段人物设定全文快照 */
    private String description;
}
