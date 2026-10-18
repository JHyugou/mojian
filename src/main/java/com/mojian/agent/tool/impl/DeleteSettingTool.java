package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.tool.AgentTool;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 策划 Agent 删除世界观设定。
 *
 * <p>删除不可逆；系统提示词要求策划先说明后果并得到用户明确同意。
 * 这里硬校验记录属于当前项目，理由必填。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteSettingTool implements AgentTool {

    private final SettingService settingService;

    @Override
    public String name() {
        return "delete_setting";
    }

    @Override
    public String description() {
        return "删除一条世界观设定（不可恢复）。"
                + "必须先向用户说明将删除哪条设定、会影响哪些已有内容，并得到用户明确同意后才能调用。"
                + "settingId 是设定记录 ID，先用 query_setting 查到；reason 必填。";
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
                        "settingId", Map.of("type", "integer",
                                "description", "设定记录 ID（query_setting 查到的记录 ID）"),
                        "reason", Map.of("type", "string",
                                "description", "删除理由；用户确认时看到的就是它")),
                "required", List.of("settingId", "reason"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Long settingId = longOf(arguments.get("settingId"));
        String reason = text(arguments.get("reason"));
        if (settingId == null) {
            return "错误：settingId 必须是设定记录 ID（整数）。";
        }
        if (!StringUtils.hasText(reason)) {
            return "错误：必须提供 reason，说明为什么要删除这条设定。";
        }
        SettingEntry setting = settingService.requireById(settingId);
        if (!projectId.equals(setting.getProjectId())) {
            return "错误：设定 " + settingId + " 不属于当前项目，不能删除。";
        }
        settingService.delete(settingId);
        log.warn("策划 Agent 删除设定 {}「{}」：{}", settingId, setting.getTitle(), reason);
        return "已删除设定「" + setting.getTitle() + "」（记录 ID " + settingId + "）。\n"
                + "风险提示：设定已从设定库移除，不可恢复；已写正文里的相关内容不会自动改写。\n"
                + "请明确告诉作者你删除了哪条设定、为什么。";
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
