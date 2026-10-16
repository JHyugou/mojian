package com.mojian.agent.llm;

import java.util.List;

/**
 * 模型这一轮的回答 —— 只有两种可能：
 *
 * <ol>
 *   <li><b>要求调工具</b>（toolCalls 非空）→ 循环继续</li>
 *   <li><b>直接给答案</b>（toolCalls 为空）→ 循环结束（这就是"正常出口"）</li>
 * </ol>
 */
public record AgentLlmResponse(String content, List<AgentToolCall> toolCalls) {

    public AgentLlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    /** 正常出口：模型不需要工具了，直接回答 */
    public static AgentLlmResponse answer(String content) {
        return new AgentLlmResponse(content, List.of());
    }

    /** 模型要求调工具 */
    public static AgentLlmResponse calls(List<AgentToolCall> toolCalls) {
        return new AgentLlmResponse(null, toolCalls);
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }
}
