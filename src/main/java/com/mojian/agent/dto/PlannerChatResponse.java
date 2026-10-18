package com.mojian.agent.dto;

import com.mojian.agent.AgentResult;
import com.mojian.agent.AgentStep;
import com.mojian.agent.context.AgentContextReport;
import com.mojian.agent.llm.TokenUsage;

import java.util.List;

/**
 * 策划对话响应。
 *
 * <p>除了回答，还带上 trace（它查了什么）、usage（花了多少）、
 * contextSummary（上下文装填情况）—— 这几样是 Agent 可观测性的最小集合。
 *
 * @param conversationId 本次对话的会话 id，前端要继续对话就把它带回来
 * @param reasoning      模型的思考过程全文；不支持思考的模型为空。
 *                       是否显示、怎么显示由界面设置决定 ——
 *                       后端一律原样返回，不在这一层做取舍。
 * @param autoApproved       本轮由后端自动采纳的草稿标题（作者开启自动采纳时）
 * @param autoApproveFailed  自动采纳失败的草稿标题/原因
 */
public record PlannerChatResponse(Long conversationId, String status, String answer, int steps,
                                  List<AgentStep> trace, TokenUsage usage, String contextSummary,
                                  String reasoning, List<String> autoApproved,
                                  List<String> autoApproveFailed) {

    public static PlannerChatResponse of(Long conversationId, AgentResult result,
                                         AgentContextReport report, String reasoning) {
        return of(conversationId, result, report, reasoning, List.of(), List.of());
    }

    public static PlannerChatResponse of(Long conversationId, AgentResult result,
                                         AgentContextReport report, String reasoning,
                                         List<String> autoApproved,
                                         List<String> autoApproveFailed) {
        return new PlannerChatResponse(conversationId, result.status().name(), result.answer(),
                result.steps(), result.trace(), result.usage(),
                report == null ? null : report.summary(),
                reasoning,
                autoApproved == null ? List.of() : List.copyOf(autoApproved),
                autoApproveFailed == null ? List.of() : List.copyOf(autoApproveFailed));
    }
}
