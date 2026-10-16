package com.mojian.agent.llm;

import java.util.List;

/**
 * 一条对话消息 —— 即"每次发给模型的包裹"里的一条。
 *
 * <p>四种角色（对齐 OpenAI 规范）：
 * <ul>
 *   <li><b>system</b>：系统提示词（规矩），你的代码写的，每次都一样</li>
 *   <li><b>user</b>：用户这次的问题</li>
 *   <li><b>assistant</b>：模型上一轮说了什么（可能带 toolCalls）</li>
 *   <li><b>tool</b>：工具的执行结果（必须带 toolCallId，否则模型对不上号）</li>
 * </ul>
 *
 * <p>模型没有记忆。这个列表就是"短期记忆"，每一轮都要<b>整份重新发过去</b>。
 */
public record AgentMessage(String role, String content, String toolCallId, List<AgentToolCall> toolCalls) {

    public static AgentMessage system(String content) {
        return new AgentMessage("system", content, null, null);
    }

    public static AgentMessage user(String content) {
        return new AgentMessage("user", content, null, null);
    }

    public static AgentMessage assistant(String content, List<AgentToolCall> toolCalls) {
        return new AgentMessage("assistant", content, null, toolCalls);
    }

    public static AgentMessage tool(String toolCallId, String content) {
        return new AgentMessage("tool", content, toolCallId, null);
    }
}
