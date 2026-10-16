package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.draft.DraftType;
import com.mojian.agent.entity.AgentDraft;
import com.mojian.agent.service.DraftService;
import com.mojian.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 起草一张新人物卡。
 *
 * <p><b>它只写草稿表，不写人物表。</b>用户在前端点确认后，才由确认接口写进
 * {@code character_profile} —— 这是"人工确认"能真正生效的前提。
 *
 * <p>"人物卡该怎么写"的规范不在这里，而在策划 Agent 的系统提示词里
 * （见 {@code AgentPrompts.planner}）。工具只管把内容存下来，不管内容好不好。
 * 知识是 Skill 的范畴，动作才是 Tool 的范畴。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftCharacterTool implements AgentTool {

    private final DraftService draftService;

    @Override
    public String name() {
        return "draft_character";
    }

    @Override
    public String description() {
        return "起草一张新人物卡，提交给用户确认。"
                + "当剧情需要引入新角色、或用户要求新增人物时使用。"
                + "调用后草稿会进入『待确认』状态，不会立刻写入人物表 —— "
                + "所以请务必在调用后向用户说明你的建议，并请其确认或修改。"
                + "人物设定要写具体、可演（参照系统提示词里的人物卡规范）。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string", "description", "人物姓名"),
                        "roleIdentity", Map.of("type", "string",
                                "description", "身份：具体到他在故事里干什么，不要只写标签"),
                        "description", Map.of("type", "string",
                                "description", "人物设定全文：外貌、性格、能力（含代价或限制）、"
                                        + "与他人的关系（双向）、语言风格等。用【】分段标题组织"),
                        "reason", Map.of("type", "string",
                                "description", "为什么建议新增这个人物：结合了资料里的哪条线索、"
                                        + "他会给故事带来什么")),
                "required", List.of("name", "roleIdentity", "description", "reason"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long conversationId = context.requireConversationId();
        String name = required(arguments, "name", "人物姓名");
        String roleIdentity = required(arguments, "roleIdentity", "身份");
        String description = required(arguments, "description", "人物设定");
        String reason = text(arguments.get("reason"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("roleIdentity", roleIdentity);
        payload.put("description", description);

        AgentDraft draft = draftService.draft(conversationId, context.requireProjectId(),
                DraftType.CHARACTER, name, payload, reason);

        return "已起草人物卡「" + name + "」，已进入待确认状态（草稿 " + draft.getId() + "），"
                + "尚未写入人物表。\n"
                + "现在请停下：用两三句话向用户说明你为什么建议加这个人物"
                + "（点明你是从资料里的哪些线索推出的），然后请用户确认、修改或放弃。"
                + "不要继续调用其他工具，也不要替用户决定。";
    }

    private String required(Map<String, Object> arguments, String key, String label) {
        String value = text(arguments.get(key));
        if (value.isEmpty()) {
            throw new IllegalArgumentException("缺少必填项 " + key + "（" + label + "）");
        }
        return value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
