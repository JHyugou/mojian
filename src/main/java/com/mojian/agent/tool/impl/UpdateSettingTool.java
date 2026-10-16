package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.draft.DraftType;
import com.mojian.agent.entity.AgentDraft;
import com.mojian.agent.service.DraftService;
import com.mojian.agent.tool.AgentTool;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修改已有的世界观设定（待用户确认）。
 *
 * <p>和 {@code draft_setting}（新增）配对：弄混会凭空多出一条重复设定，
 * 而设定重复比人物重复更麻烦 —— 两条冲突的设定同时被注入材料，
 * 模型读到的是自相矛盾的世界，写出来的正文也就自相矛盾。
 *
 * <p>只提交要改的字段，没提到的沿用原值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateSettingTool implements AgentTool {

    private final DraftService draftService;
    private final SettingService settingService;

    @Override
    public String name() {
        return "update_setting";
    }

    @Override
    public String description() {
        return "修改一条已有世界观设定的内容，提交给用户确认。"
                + "必须先用 query_setting 拿到记录 ID。"
                + "只传要改的字段，没传的保持原样。"
                + "改 content 时请给出修改后的<b>完整内容</b>，"
                + "把用户没要求改的部分原样保留进去。"
                + "如果新内容与别的设定冲突，先在回复里指出来，不要硬改。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "settingId", Map.of("type", "integer",
                                "description", "设定记录 ID（先用 query_setting 查到）"),
                        "title", Map.of("type", "string", "description", "新标题；不传表示不改"),
                        "content", Map.of("type", "string",
                                "description", "新的设定内容全文；不传表示不改"),
                        "tags", Map.of("type", "string", "description", "新标签；不传表示不改"),
                        "triggerKeywords", Map.of("type", "string",
                                "description", "新触发词；不传表示不改"),
                        "reason", Map.of("type", "string",
                                "description", "改动理由，或用户的原话")),
                "required", List.of("settingId", "reason"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long conversationId = context.requireConversationId();
        Long projectId = context.requireProjectId();

        Long settingId = longOf(arguments.get("settingId"));
        if (settingId == null) {
            return "错误：必须提供 settingId（先用 query_setting 查到记录 ID）。";
        }
        String title = text(arguments.get("title"));
        String content = text(arguments.get("content"));
        String tags = text(arguments.get("tags"));
        String triggerKeywords = text(arguments.get("triggerKeywords"));
        if (title.isEmpty() && content.isEmpty() && tags.isEmpty() && triggerKeywords.isEmpty()) {
            return "错误：至少要提供一个要修改的字段，否则这次修改没有任何内容。";
        }
        String reason = text(arguments.get("reason"));

        SettingEntry entry = settingService.requireById(settingId);
        if (!projectId.equals(entry.getProjectId())) {
            return "错误：设定 " + settingId + " 不属于本书，不能修改。请用 query_setting 重新确认 ID。";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (!title.isEmpty()) {
            payload.put("title", title);
        }
        if (!content.isEmpty()) {
            payload.put("content", content);
        }
        if (!tags.isEmpty()) {
            payload.put("tags", tags);
        }
        if (!triggerKeywords.isEmpty()) {
            payload.put("triggerKeywords", triggerKeywords);
        }

        AgentDraft draft = draftService.draft(conversationId, projectId, DraftType.SETTING,
                entry.getTitle(), payload, reason, settingId);

        return "已起草对设定「" + entry.getTitle() + "」的修改（草稿 " + draft.getId() + "），"
                + "尚未生效 —— 它会出现在『待你确认』里。\n"
                + "现在请停下：说明你想怎么改、为什么，然后请用户确认。"
                + "若这次改动可能与其它设定冲突，一并指出来。不要继续调用其他工具。";
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
