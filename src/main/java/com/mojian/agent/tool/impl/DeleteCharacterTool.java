package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.service.AgentDeleteRequestService;
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
 * <p>删除是不可逆动作：工具只登记删除申请，作者在界面上确认后才真正删除。
 * 这样即使模型误判，也不会直接动作者的人物档案。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteCharacterTool implements AgentTool {

    private final CharacterService characterService;
    private final AgentDeleteRequestService deleteRequestService;

    @Override
    public String name() {
        return "delete_character";
    }

    @Override
    public String description() {
        return "申请人物的删除。此工具不会立即删除，只会生成一条待作者确认的删除申请；"
                + "请先向用户说明将删除哪个人物、会连带删除什么。"
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
        characterService.requireById(characterId);
        deleteRequestService.request(projectId, context.conversationId(),
                "CHARACTER", characterId, character.getName(), reason);
        log.warn("策划 Agent 申请删除人物 {}「{}」：{}", characterId, character.getName(), reason);
        return "已提交删除人物卡「" + character.getName() + "」的申请，尚未删除。\n"
                + "请让作者在待确认区点击确认；确认前它不会影响任何数据。";
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
