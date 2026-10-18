package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.service.AgentDeleteRequestService;
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
 * <p>删除不可逆：工具只登记删除申请，作者确认后才真正删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteSettingTool implements AgentTool {

    private final SettingService settingService;
    private final AgentDeleteRequestService deleteRequestService;

    @Override
    public String name() {
        return "delete_setting";
    }

    @Override
    public String description() {
        return "申请删除一条世界观设定。此工具不会立即删除，只会生成待作者确认的删除申请。"
                + "请先向用户说明将删除哪条设定、会影响哪些已有内容。"
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
        settingService.requireById(settingId);
        deleteRequestService.request(projectId, context.conversationId(),
                "SETTING", settingId, setting.getTitle(), reason);
        log.warn("策划 Agent 申请删除设定 {}「{}」：{}", settingId, setting.getTitle(), reason);
        return "已提交删除设定「" + setting.getTitle() + "」的申请，尚未删除。\n"
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
