package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.draft.DraftType;
import com.mojian.agent.entity.AgentDraft;
import com.mojian.agent.service.DraftService;
import com.mojian.agent.tool.AgentTool;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修改已有的人物卡（待用户确认）。
 *
 * <p>和 {@code draft_character}（新增）的区别，等同于 {@code update_blueprint}
 * 和 {@code save_blueprint} 的区别 —— 弄混就会凭空多出一张重复的人物卡。
 * 所以这里必须显式给记录 ID，而且要校验它属于本项目。
 *
 * <p>只提交要改的字段：没提到的沿用原值。
 * 这一点在人物卡上尤其要紧 —— 作者往往只想补一句"他的左手有旧伤"，
 * 不该因此把整个性格设定重写一遍。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateCharacterTool implements AgentTool {

    private final DraftService draftService;
    private final CharacterService characterService;

    @Override
    public String name() {
        return "update_character";
    }

    @Override
    public String description() {
        return "修改一个已有人物卡的内容，提交给用户确认。"
                + "必须先用 query_character 拿到记录 ID。"
                + "只传要改的字段，没传的字段保持原样。"
                + "name 是人物姓名，也是人物卡标题；改名时必须同时传 name，不能只改正文里的称呼。"
                + "修改 description（人物设定全文）时，请给出修改后的<b>完整内容</b>，"
                + "并把用户没要求改的部分原样保留进去。";
    }

    @Override
    public java.util.List<String> roles() {
        return java.util.List.of("PLANNER");
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "characterId", Map.of("type", "integer",
                                "description", "人物记录 ID（先用 query_character 查到）"),
                        "name", Map.of("type", "string",
                                "description", "新的姓名（同时是人物卡标题）；不传表示不改名"),
                        "roleIdentity", Map.of("type", "string",
                                "description", "新的身份定位；不传表示不改"),
                        "description", Map.of("type", "string",
                                "description", "新的人物设定全文（完整内容，不是差异片段）；不传表示不改"),
                        "reason", Map.of("type", "string",
                                "description", "改动理由，或用户的原话")),
                "required", List.of("characterId", "reason"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long conversationId = context.requireConversationId();
        Long projectId = context.requireProjectId();

        Long characterId = longOf(arguments.get("characterId"));
        if (characterId == null) {
            return "错误：必须提供 characterId（先用 query_character 查到记录 ID）。";
        }
        String name = text(arguments.get("name"));
        String roleIdentity = text(arguments.get("roleIdentity"));
        String description = text(arguments.get("description"));
        if (name.isEmpty() && roleIdentity.isEmpty() && description.isEmpty()) {
            return "错误：name、roleIdentity、description 至少要提供一个，否则这次修改没有任何内容。";
        }
        if (name.length() > 50) {
            return "错误：人物姓名不能超过 50 字。";
        }
        String reason = text(arguments.get("reason"));

        CharacterProfile profile = characterService.requireById(characterId);
        // 越界保护：模型给错 id 时，绝不能改到别的项目的人物
        if (!projectId.equals(profile.getProjectId())) {
            return "错误：人物 " + characterId + " 不属于本书，不能修改。请用 query_character 重新确认 ID。";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (!name.isEmpty()) {
            payload.put("name", name);
        }
        if (!roleIdentity.isEmpty()) {
            payload.put("roleIdentity", roleIdentity);
        }
        if (!description.isEmpty()) {
            payload.put("description", description);
        }

        // 改名时草稿标题也同步成新名字，待确认列表才看起来对
        String draftTitle = name.isEmpty() ? profile.getName() : name;
        AgentDraft draft = draftService.draft(conversationId, projectId, DraftType.CHARACTER,
                draftTitle, payload, reason, characterId);

        String changeSummary = name.isEmpty()
                ? "修改人物卡「" + profile.getName() + "」的内容"
                : "把人物卡「" + profile.getName() + "」改名为「" + name + "」";
        return "已起草" + changeSummary + "（草稿 " + draft.getId() + "），"
                + "尚未生效 —— 它会出现在『待你确认』里。\n"
                + "现在请停下：用两三句话说明你想改什么、为什么，"
                + "然后请用户确认。不要继续调用其他工具。";
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
        return value == null || !StringUtils.hasText(String.valueOf(value))
                ? "" : String.valueOf(value).trim();
    }
}
