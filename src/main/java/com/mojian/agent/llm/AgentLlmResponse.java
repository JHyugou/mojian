package com.mojian.agent.llm;

import java.util.List;

/**
 * 模型这一轮的回答 —— 只有两种可能：
 *
 * <ol>
 *   <li><b>要求调工具</b>（toolCalls 非空）→ 循环继续</li>
 *   <li><b>直接给答案</b>（toolCalls 为空）→ 循环结束（这就是"正常出口"）</li>
 * </ol>
 *
 * @param usage 这一轮实际消耗的 token。循环会把各轮累加，最后落到用量流水表，
 *              让"这次运行花了多少"可见 —— 额度是硬约束，不能靠感觉
 */
public record AgentLlmResponse(String content, List<AgentToolCall> toolCalls, TokenUsage usage) {

    public AgentLlmResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        usage = usage == null ? TokenUsage.NONE : usage;
    }

    /** 正常出口：模型不需要工具了，直接回答 */
    public static AgentLlmResponse answer(String content) {
        return new AgentLlmResponse(content, List.of(), TokenUsage.NONE);
    }

    /** 模型要求调工具 */
    public static AgentLlmResponse calls(List<AgentToolCall> toolCalls) {
        return new AgentLlmResponse(null, toolCalls, TokenUsage.NONE);
    }

    public AgentLlmResponse withUsage(TokenUsage actualUsage) {
        return new AgentLlmResponse(content, toolCalls, actualUsage);
    }

    public boolean wantsTools() {
        return !toolCalls.isEmpty();
    }
}
