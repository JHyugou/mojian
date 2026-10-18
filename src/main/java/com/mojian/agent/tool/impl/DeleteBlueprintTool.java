package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
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
 * <p>删除不可逆，且可能连带子节点、解除章节关联；系统提示词要求先说明后果并得到明确同意。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteBlueprintTool implements AgentTool {

    private final OutlineService outlineService;

    @Override
    public String name() {
        return "delete_blueprint";
    }

    @Override
    public String description() {
        return "删除一个蓝图节点（不可恢复）。会连带删除其子节点，并解除关联章节的蓝图关联；正文不会删除。"
                + "必须先向用户说明将删除哪个节点、会影响哪些子节点和章节，并得到用户明确同意后才能调用。"
                + "nodeId 是蓝图节点记录 ID，先用 query_outline 查到；reason 必填。";
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
        outlineService.delete(nodeId);
        log.warn("策划 Agent 删除蓝图节点 {}「{}」：{}", nodeId, node.getName(), reason);
        return "已删除蓝图节点「" + node.getName() + "」（记录 ID " + nodeId + "）。\n"
                + "风险提示：其子节点已一并删除，关联章节的蓝图关联已解除，正文未动。不可恢复。\n"
                + "请明确告诉作者你删除了哪个节点、为什么，以及后续章节如何调整。";
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
