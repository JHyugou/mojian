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
 * 起草一条新的世界观设定。
 *
 * <p>同样只写草稿表，等用户确认后才进 {@code setting_entry}。
 *
 * <p>{@code tags} / {@code triggerKeywords} 不是摆设：墨笺的设定表本来就有这两个字段
 * （用于关键词检索）。让 Agent 顺手填上，将来材料装不下要筛设定时就是现成的索引。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DraftSettingTool implements AgentTool {

    private final DraftService draftService;

    @Override
    public String name() {
        return "draft_setting";
    }

    @Override
    public String description() {
        return "起草一条新的世界观设定，提交给用户确认。"
                + "当剧情需要引入新的规则、地点、组织、器物等设定时使用。"
                + "调用后草稿进入『待确认』状态，不会立刻写入设定表 —— "
                + "请务必在调用后向用户说明并请其确认。"
                + "注意：如果新设定与已有设定冲突，不要硬写，先在回复里向用户指出。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of("type", "string", "description", "设定名（一条设定只讲一件事）"),
                        "content", Map.of("type", "string",
                                "description", "设定内容：它怎么运作、有什么代价或限制、谁知道它、"
                                        + "会影响哪些人物或势力"),
                        "tags", Map.of("type", "string",
                                "description", "分类标签，逗号或顿号分隔（例如：修炼、组织、地理）"),
                        "triggerKeywords", Map.of("type", "string",
                                "description", "触发词：正文里出现这些词时通常需要这条设定，"
                                        + "逗号分隔。用于将来按关键词筛设定"),
                        "reason", Map.of("type", "string",
                                "description", "为什么需要这条设定：解决什么问题、支撑哪段剧情")),
                "required", List.of("title", "content", "reason"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long conversationId = context.requireConversationId();
        String title = required(arguments, "title", "设定名");
        String content = required(arguments, "content", "设定内容");
        String reason = text(arguments.get("reason"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("content", content);
        payload.put("tags", text(arguments.get("tags")));
        payload.put("triggerKeywords", text(arguments.get("triggerKeywords")));

        AgentDraft draft = draftService.draft(conversationId, context.requireProjectId(),
                DraftType.SETTING, title, payload, reason);

        return "已起草设定「" + title + "」，已进入待确认状态（草稿 " + draft.getId() + "），"
                + "尚未写入设定表。\n"
                + "现在请停下：用两三句话说明这条设定解决什么问题、为什么现在需要它，"
                + "然后请用户确认、修改或放弃。若它与已有设定可能冲突，一并指出来。"
                + "不要继续调用其他工具。";
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
