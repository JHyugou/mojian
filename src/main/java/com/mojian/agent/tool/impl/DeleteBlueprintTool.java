package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.service.AgentDeleteRequestService;
import com.mojian.agent.tool.AgentTool;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 策划 Agent 删除蓝图节点。
 *
 * <p>删除不可逆，且可能连带子节点、解除章节关联；工具只登记删除申请，作者确认后才真正删除。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteBlueprintTool implements AgentTool {

    private final OutlineService outlineService;
    private final AgentDeleteRequestService deleteRequestService;

    @Override
    public String name() {
        return "delete_blueprint";
    }

    @Override
    public String description() {
        return "申请删除一个蓝图节点。此工具不会立即删除，只会生成待作者确认的删除申请。"
                + "会连带删除其子节点，并解除关联章节的蓝图关联；正文不会删除。"
                + "请先向用户说明影响范围。nodeId 先用 query_outline 查到；reason 必填。";
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
                        "nodeId", Map.of("type", "integer",
                                "description", "蓝图节点记录 ID（query_outline 查到的记录 ID）"),
                        "reason", Map.of("type", "string",
                                "description", "删除理由；用户确认时看到的就是它")),
                "required", List.of("nodeId", "reason"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Long nodeId = longOf(arguments.get("nodeId"));
        String reason = text(arguments.get("reason"));
        if (nodeId == null) {
            return "错误：nodeId 必须是蓝图节点记录 ID（整数）。";
        }
        if (!StringUtils.hasText(reason)) {
            return "错误：必须提供 reason，说明为什么要删除这个蓝图节点。";
        }
        PlotOutline node = outlineService.requireById(nodeId);
        if (!projectId.equals(node.getProjectId())) {
            return "错误：蓝图节点 " + nodeId + " 不属于当前项目，不能删除。";
        }
        outlineService.requireById(nodeId);
        deleteRequestService.request(projectId, context.conversationId(),
                "OUTLINE", nodeId, node.getName(), reason);
        log.warn("策划 Agent 申请删除蓝图节点 {}「{}」：{}", nodeId, node.getName(), reason);
        return "已提交删除蓝图节点「" + node.getName() + "」的申请，尚未删除。\n"
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
