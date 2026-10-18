package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.tool.AgentTool;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 策划 Agent 删除人物卡。
 *
 * <p>删除是不可逆动作，工具本身不提供"待确认"状态 —— 确认责任在系统提示词：
 * 策划必须先向用户说明后果并得到明确同意，才能调用。
 * 这里再做一层硬校验：记录必须属于当前项目，理由必填。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteCharacterTool implements AgentTool {

    private final CharacterService characterService;

    @Override
    public String name() {
        return "delete_character";
    }

    @Override
    public String description() {
        return "删除一张人物卡（人物卡及其版本节点一并删除，不可恢复）。"
                + "必须先向用户说明将删除哪个人物、会连带删除什么，并得到用户明确同意后才能调用。"
                + "characterId 是人物记录 ID，先用 query_character 查到；reason 必填。";
    }

    @Override
    public List<String> roles() {
        return List.of("PLANNER");
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "characterId", Map.of("type", "integer",
                                "description", "人物记录 ID（query_character 查到的记录 ID）"),
                        "reason", Map.of("type", "string",
                                "description", "删除理由；必须具体，用户在确认时看到的就是它")),
                "required", List.of("characterId", "reason"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Long characterId = longOf(arguments.get("characterId"));
        String reason = text(arguments.get("reason"));
        if (characterId == null) {
            return "错误：characterId 必须是人物记录 ID（整数）。";
        }
        if (!StringUtils.hasText(reason)) {
            return "错误：必须提供 reason，说明为什么要删除这个人物。";
        }
        CharacterProfile character = characterService.requireById(characterId);
        if (!projectId.equals(character.getProjectId())) {
            return "错误：人物 " + characterId + " 不属于当前项目，不能删除。";
        }
        characterService.delete(characterId);
        log.warn("策划 Agent 删除人物 {}「{}」：{}", characterId, character.getName(), reason);
        return "已删除人物卡「" + character.getName() + "」（记录 ID " + characterId + "）。\n"
                + "风险提示：人物卡与其版本节点已一并删除，不可恢复。\n"
                + "请明确告诉作者你删除了谁、为什么；如果还需要这个人物，只能重新建档。";
    }

    private Long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
