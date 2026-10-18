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
 * 把剧情蓝图落成蓝图树的一个节点（待确认）。
 *
 * <p><b>这里是双 Agent 架构的交接点。</b>策划 Agent 想清楚的东西，
 * 到写作 Agent 手里只剩这份蓝图 —— 所以它必须写到"可执行"的程度，
 * 而且要带上<b>决策理由</b>：
 *
 * <pre>
 * 单 Agent 时，"为什么方案二是旧识而不是仇敌"这种取舍天然留在它自己上下文里；
 * 双 Agent 时，这份理由只有写进蓝图才传得过去，不写就永久丢失，
 * 写作 Agent 只能自己猜 —— 猜出来的和策划想的往往不是一回事。
 * </pre>
 *
 * <p>决策理由会被 {@code DraftService} 写进蓝图节点的摘要，跟着蓝图一起传给写作 Agent。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaveBlueprintTool implements AgentTool {

    private final DraftService draftService;

    @Override
    public String name() {
        return "save_blueprint";
    }

    @Override
    public String description() {
        return "把确定的剧情走向落成蓝图节点（章节级别的剧情规划），提交给用户确认。"
                + "当用户已经选定某条走向、需要把它固化成后续写作的依据时使用。"
                + "可以通过 parentId 挂到某个卷/段节点下（不传则是根节点）。"
                + "调用后草稿进入『待确认』状态，不会立刻写入蓝图树。"
                + "内容要写到『可执行』的程度：在哪里、谁在场、各自动机、冲突是什么、"
                + "改变了什么、给后面留下什么钩子，以及为什么选这条而不是别的。";
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
                        "name", Map.of("type", "string", "description", "节点名，通常就是章节名"),
                        "nodeType", Map.of("type", "string",
                                "description", "节点类型：VOLUME（卷）/ ARC（段落）/ CHAPTER（章）。默认 CHAPTER"),
                        "parentId", Map.of("type", "integer",
                                "description", "可选：父节点 ID（先用 query_outline 查到）。"
                                        + "建章节节点时挂到所属的卷/段下；不传则作为根节点"),
                        "sortOrder", Map.of("type", "integer",
                                "description", "可选：同级排序，数字小的在前；不传按 0 处理"),
                        "summary", Map.of("type", "string",
                                "description", "一句话概括这段剧情要发生什么"),
                        "detail", Map.of("type", "string",
                                "description", "详细走向：地点与时间、在场人物及各自动机、"
                                        + "冲突是什么、这段结束时改变了什么、给后面留下什么钩子"),
                        "reason", Map.of("type", "string",
                                "description", "为什么选这条走向而不是别的：取舍的理由。"
                                        + "这份理由会随蓝图一起交给写作环节，务必写清")),
                "required", List.of("name", "summary", "detail", "reason"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long conversationId = context.requireConversationId();
        String name = required(arguments, "name", "节点名");
        String summary = required(arguments, "summary", "一句话概括");
        String detail = required(arguments, "detail", "详细走向");
        String reason = required(arguments, "reason", "决策理由");
        String nodeType = arguments.get("nodeType") == null
                ? "CHAPTER" : String.valueOf(arguments.get("nodeType")).trim();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("nodeType", nodeType);
        Long parentId = longOf(arguments.get("parentId"));
        if (parentId != null) {
            payload.put("parentId", parentId);
        }
        Integer sortOrder = intOf(arguments.get("sortOrder"));
        if (sortOrder != null) {
            payload.put("sortOrder", sortOrder);
        }
        payload.put("summary", summary);
        payload.put("detail", detail);

        AgentDraft draft = draftService.draft(conversationId, context.requireProjectId(),
                DraftType.BLUEPRINT, name, payload, reason);

        return "已起草蓝图节点「" + name + "」，已进入待确认状态（草稿 " + draft.getId() + "），"
                + "尚未写入蓝图树。\n"
                + "现在请停下：用两三句话向用户复述这段剧情的核心（要发生什么、改变了什么），"
                + "然后请用户确认或修改。"
                + "确认之后，这份蓝图会成为写作环节的依据。不要继续调用其他工具。";
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

    private Long longOf(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Integer intOf(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
