package com.mojian.agent.dto;

import com.mojian.agent.AgentResult;
import com.mojian.agent.AgentStep;

import java.util.List;

/**
 * Agent 调试入口的响应。
 *
 * <p>除了答案，还返回 status / steps / trace —— 这三样是 Agent 可观测性的最小集合：
 * 用户能看到"它干了什么"，开发者能看到"它哪一步想歪了"。
 *
 * @param status 出口类型：FINISHED（正常结束）或 MAX_STEPS（被刹车）
 * @param answer 给用户的回答
 * @param steps  实际走了几步
 * @param trace  每一步的工具调用记录
 */
public record AgentChatResponse(String status, String answer, int steps, List<AgentStep> trace) {

    public static AgentChatResponse from(AgentResult result) {
        return new AgentChatResponse(result.status().name(), result.answer(), result.steps(), result.trace());
    }
}
