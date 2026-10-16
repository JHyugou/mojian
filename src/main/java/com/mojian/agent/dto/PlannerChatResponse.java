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
 * contextSummary（上下文装填情况）—— 这三样是 Agent 可观测性的最小集合。
 *
 * @param conversationId 本次对话的会话 id，前端要继续对话就把它带回来
 */
public record PlannerChatResponse(Long conversationId, String status, String answer, int steps,
                                  List<AgentStep> trace, TokenUsage usage, String contextSummary) {

    public static PlannerChatResponse of(Long conversationId, AgentResult result, AgentContextReport report) {
        return new PlannerChatResponse(conversationId, result.status().name(), result.answer(),
                result.steps(), result.trace(), result.usage(),
                report == null ? null : report.summary());
    }
}
