package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.tool.AgentTool;
import com.mojian.outline.service.OutlineService;
import com.mojian.outline.vo.OutlineNodeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 查剧情蓝图节点（返回节点 ID）。
 *
 * <p><b>它是"改蓝图"这条路的前半段</b>：用户框选一段蓝图说"这里改成白天"，
 * 模型首先得知道那是哪个节点。聊天里说的是"第一章那个开头"，
 * 而改数据需要的是节点 ID —— 这个工具就是两者的翻译。
 *
 * <p>材料里已经有蓝图了（截断到 200 字），但那份不带 ID，而且内容不全。
 * 要动手改，就得先拿到准确的定位。
 */
@Component
@RequiredArgsConstructor
public class QueryOutlineTool implements AgentTool {

    private final OutlineService outlineService;

    @Override
    public String name() {
        return "query_outline";
    }

    @Override
    public String description() {
        return "查询本书的剧情蓝图节点，返回每个节点的 ID、类型、名称与内容概要。"
                + "当用户要求修改某段蓝图，而你不确定具体是哪个节点时，"
                + "先用本工具找到它并记下节点 ID，再用 update_blueprint 提出修改。"
                + "参数 keyword 可留空，表示列出全部节点。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "keyword", Map.of("type", "string",
                                "description", "按名称或内容过滤，例如「第一章」；留空列出全部")),
                "required", List.of());
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        List<OutlineNodeVO> tree = outlineService.tree(projectId);
        if (tree == null || tree.isEmpty()) {
            return "本书还没有任何蓝图节点。";
        }

        String keyword = arguments.get("keyword") == null
                ? "" : String.valueOf(arguments.get("keyword")).trim();

        List<OutlineNodeVO> flat = new ArrayList<>();
        flatten(tree, flat);

        StringBuilder sb = new StringBuilder();
        int matched = 0;
        for (OutlineNodeVO node : flat) {
            String name = node.getName() == null ? "" : node.getName();
            String summary = node.getSummary() == null ? "" : node.getSummary();
            if (!keyword.isEmpty() && !name.contains(keyword) && !summary.contains(keyword)) {
                continue;
            }
            matched++;
            sb.append("■ [节点ID ").append(node.getId()).append("] ").append(name);
            if (StringUtils.hasText(node.getNodeType())) {
                sb.append("（").append(node.getNodeType()).append("）");
            }
            sb.append('\n');
            if (StringUtils.hasText(summary)) {
                // 概要过长时截断：这个工具的用途是"定位"，不是"通读"
                sb.append(summary.trim().length() > 600
                        ? summary.trim().substring(0, 600) + "……\n"
                        : summary.trim() + "\n");
            }
        }

        if (matched == 0) {
            return "没有匹配「" + keyword + "」的蓝图节点。本书现有 "
                    + flat.size() + " 个节点，可以换个关键词再查。";
        }
        return sb.toString().trim();
    }

    private void flatten(List<OutlineNodeVO> nodes, List<OutlineNodeVO> out) {
        for (OutlineNodeVO node : nodes) {
            out.add(node);
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                flatten(node.getChildren(), out);
            }
        }
    }
}
