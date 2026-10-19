package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.draft.DraftType;
import com.mojian.agent.entity.AgentDraft;
import com.mojian.agent.service.DraftService;
import com.mojian.agent.tool.AgentTool;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修改已有的蓝图节点（待用户确认）。
 *
 * <p><b>它是"引用式改稿"的落点。</b>用户框选蓝图里的一段，说「这里改成白天」，
 * 最终要落到"把某个节点的内容换成新的"这个动作上 —— 就是这个工具。
 *
 * <p>和 {@code save_blueprint} 的区别：那个是<b>新增</b>节点，这个是<b>改</b>已有节点。
 * 弄混了会凭空多出一个重复节点，或者在错误的节点上改动。
 * 所以这里<b>必须显式给节点 ID</b>，而且要先校验它真的属于本项目 ——
 * 模型说错一个数字，不该改到别人书里。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateBlueprintTool implements AgentTool {

    private final DraftService draftService;
    private final OutlineService outlineService;

    @Override
    public String name() {
        return "update_blueprint";
    }

    @Override
    public String description() {
        return "修改一个已有蓝图节点的名称或内容，提交给用户确认。"
                + "当用户要求改节点名（例如把「第六章 不留手」改成「第六章 决赛」）或改动某段已规划的剧情时使用。"
                + "必须先用 query_outline 找到目标节点的 ID。"
                + "name 和 summary 至少给一个；没提到的字段会保持原样。"
                + "调用后草稿进入『待确认』状态，不会立刻改动蓝图。";
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
                                "description", "要修改的蓝图节点 ID（先用 query_outline 查到）"),
                        "name", Map.of("type", "string",
                                "description", "新的节点名称；只改名时传它，不传 summary 即可"),
                        "summary", Map.of("type", "string",
                                "description", "节点的新内容（完整内容，不是差异片段）。"
                                        + "要保留原内容里没被要求改的部分，把它们一起写进来"),
                        "reason", Map.of("type", "string",
                                "description", "改动理由，或者用户的原话引用")),
                "required", List.of("nodeId", "reason"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long conversationId = context.requireConversationId();
        Long projectId = context.requireProjectId();

        Long nodeId = longOf(arguments.get("nodeId"));
        if (nodeId == null) {
            return "错误：必须提供 nodeId（先用 query_outline 查到目标节点的 ID）。";
        }
        String name = text(arguments.get("name"));
        String summary = text(arguments.get("summary"));
        if (name.isEmpty() && summary.isEmpty()) {
            return "错误：name 和 summary 至少要提供一个，否则这次修改没有任何内容。";
        }
        if (name.length() > 200) {
            return "错误：蓝图节点名称不能超过 200 字。";
        }
        String reason = text(arguments.get("reason"));

        PlotOutline node = outlineService.requireById(nodeId);
        // 越界保护：模型给错 id 时，绝不能改到别的项目的数据
        if (!projectId.equals(node.getProjectId())) {
            return "错误：节点 " + nodeId + " 不属于本书，不能修改。请用 query_outline 重新确认 ID。";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (!name.isEmpty()) {
            payload.put("name", name);
        }
        payload.put("nodeType", node.getNodeType());
        if (!summary.isEmpty()) {
            payload.put("summary", summary);
        }

        String draftTitle = name.isEmpty() ? node.getName() : name;
        AgentDraft draft = draftService.draft(conversationId, projectId, DraftType.BLUEPRINT,
                draftTitle, payload, reason, nodeId);

        String change = name.isEmpty()
                ? "修改蓝图节点「" + node.getName() + "」的内容"
                : "把蓝图节点「" + node.getName() + "」改名为「" + name + "」";
        return "已起草" + change + "（草稿 " + draft.getId() + "），"
                + "尚未生效 —— 它会出现在『待你确认』里。\n"
                + "现在请停下：用两三句话向用户说明你打算怎么改、为什么这么改"
                + "（如果用户框选了一段原文，请复述那一段，让他确认你理解对了位置）。"
                + "不要继续调用其他工具。";
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
