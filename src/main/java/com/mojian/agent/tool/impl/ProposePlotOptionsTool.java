package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.entity.AgentPlanOption;
import com.mojian.agent.service.ConversationService;
import com.mojian.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 让策划 Agent 把剧情走向"摆到桌面上给用户挑"。
 *
 * <p>这是整个交互区最核心的一环。没有它，模型要么替用户拍板（用户失去掌控），
 * 要么把几条方案混在散文里（用户读起来累、也没法点选）。
 * 有了它，方案会结构化落库，前端能渲染成卡片。
 *
 * <h3>为什么这个工具不需要"人工确认"</h3>
 * 人工确认是给<b>改变故事状态</b>的操作准备的（写蓝图、建人物卡）。
 * 而"展示选项"不改变任何东西，它只是把选择权交还给用户 ——
 * <b>它本身就是人工确认环节的一部分</b>。
 *
 * <h3>关于"停下来等用户"</h3>
 * 工具的返回文字会明确要求模型停下。模型看到后通常会给出收尾答复（不再调工具），
 * 循环就从"正常出口"结束了 —— 正好对应我们要的"问完就等"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProposePlotOptionsTool implements AgentTool {

    private final ConversationService conversationService;

    @Override
    public String name() {
        return "propose_plot_options";
    }

    @Override
    public String description() {
        return "向用户展示若干条剧情走向方案，供其选择或提出修改意见。"
                + "当用户提出一个会影响故事后续发展的设想、需要确定走向时使用本工具。"
                + "必须给出同一维度上的不同取向（例如'剑客是旧识'/'剑客是仇敌'/'剑客是卧底'），"
                + "而不是泛泛的描述。给出方案后要停下等待用户选择，不要自行推进。";
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
                        "question", Map.of(
                                "type", "string",
                                "description", "这次要用户拍板的问题，一句话（例如'第三章的剑客是什么来头'）"),
                        "options", Map.of(
                                "type", "array",
                                "description", "2~4 条走向方案",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "title", Map.of("type", "string",
                                                        "description", "方案名，6~12 字，要能一眼看出取向"),
                                                "summary", Map.of("type", "string",
                                                        "description", "一句话简介，30~50 字，卡片正面显示"),
                                                "detail", Map.of("type", "string",
                                                        "description", "详细走向：会发生什么、改变了什么、给后面留下什么钩子")),
                                        "required", List.of("title", "summary", "detail")))),
                "required", List.of("question", "options"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long conversationId = context.requireConversationId();
        String question = textOf(arguments.get("question"));
        List<ConversationService.PlanOptionDraft> drafts = parseOptions(arguments.get("options"));

        List<AgentPlanOption> saved = conversationService.savePlanOptions(conversationId, null, drafts);
        log.info("策划给出 {} 条方案（会话 {}）：{}", saved.size(), conversationId,
                saved.stream().map(AgentPlanOption::getTitle).toList());

        return "已向用户展示 " + saved.size() + " 条方案"
                + (question.isEmpty() ? "" : "，待决定的问题：" + question)
                + "。\n"
                + "现在请停下来：用一两句话点出这些方案的<b>关键差别</b>，"
                + "然后明确请用户选一条，或写下他自己的修改意见。"
                + "不要自行选择其中一条继续推进，也不要在这一轮里继续调用其他工具。";
    }

    /**
     * 解析模型给出的方案数组。
     *
     * <p>模型偶尔会漏字段、或把 options 写成字符串。这里做宽容解析：
     * 缺 title 的条目直接丢弃（没有名字的方案在界面上没法显示），
     * 但一条都没有时明确报错 —— 让"错误回传"机制把问题告诉模型，它有机会自己纠正。
     */
    private List<ConversationService.PlanOptionDraft> parseOptions(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("options 必须是非空数组");
        }
        List<ConversationService.PlanOptionDraft> drafts = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String title = textOf(map.get("title"));
            if (title.isEmpty()) {
                continue;
            }
            drafts.add(new ConversationService.PlanOptionDraft(
                    title, textOf(map.get("summary")), textOf(map.get("detail"))));
        }
        if (drafts.isEmpty()) {
            throw new IllegalArgumentException("options 里没有一条带 title 的有效方案");
        }
        return drafts;
    }

    private String textOf(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
