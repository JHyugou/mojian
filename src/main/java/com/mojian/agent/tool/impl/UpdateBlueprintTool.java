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
        return "修改一个已有蓝图节点的内容，提交给用户确认。"
                + "当用户要求改动某段已经规划好的剧情（例如框选了一段蓝图说『这里改成……』）时使用。"
                + "必须先用 query_outline 找到目标节点的 ID。"
                + "只需要给出要改的部分：没提到的字段会保持原样。"
                + "调用后草稿进入『待确认』状态，不会立刻改动蓝图。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "nodeId", Map.of("type", "integer",
                                "description", "要修改的蓝图节点 ID（先用 query_outline 查到）"),
                        "summary", Map.of("type", "string",
                                "description", "节点的新内容（完整内容，不是差异片段）。"
                                        + "要保留原内容里没被要求改的部分，把它们一起写进来"),
                        "reason", Map.of("type", "string",
                                "description", "改动理由，或者用户的原话引用")),
                "required", List.of("nodeId", "summary", "reason"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long conversationId = context.requireConversationId();
        Long projectId = context.requireProjectId();

        Long nodeId = longOf(arguments.get("nodeId"));
        if (nodeId == null) {
            return "错误：必须提供 nodeId（先用 query_outline 查到目标节点的 ID）。";
        }
        String summary = text(arguments.get("summary"));
        if (summary.isEmpty()) {
            return "错误：必须提供 summary（节点的新内容）。";
        }
        String reason = text(arguments.get("reason"));

        PlotOutline node = outlineService.requireById(nodeId);
        // 越界保护：模型给错 id 时，绝不能改到别的项目的数据
        if (!projectId.equals(node.getProjectId())) {
            return "错误：节点 " + nodeId + " 不属于本书，不能修改。请用 query_outline 重新确认 ID。";
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", node.getName());
        payload.put("nodeType", node.getNodeType());
        payload.put("summary", summary);

        AgentDraft draft = draftService.draft(conversationId, projectId, DraftType.BLUEPRINT,
                node.getName(), payload, reason, nodeId);

        return "已起草对蓝图节点「" + node.getName() + "」的修改（草稿 " + draft.getId() + "），"
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
