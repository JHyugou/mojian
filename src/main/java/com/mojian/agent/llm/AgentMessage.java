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
public record AgentMessage(String role, String content, String toolCallId,
                           List<AgentToolCall> toolCalls, List<String> images) {

    public AgentMessage {
        images = images == null ? List.of() : List.copyOf(images);
    }

    /*
     * 角色名做成常量：它们会出现在好几处判断里
     * （比如"读长期历史时要跳过工具结果"），散落的字面量迟早会写错一个字母，
     * 而写错的表现是"历史被静默丢了一部分"，不报错、只是模型表现变差。
     */
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    public static AgentMessage system(String content) {
        return new AgentMessage(ROLE_SYSTEM, content, null, null, List.of());
    }

    public static AgentMessage user(String content) {
        return new AgentMessage(ROLE_USER, content, null, null, List.of());
    }

    /** 带图片的用户消息；images 是 data:image/...;base64,... 的完整 URL */
    public static AgentMessage userWithImages(String content, List<String> images) {
        return new AgentMessage(ROLE_USER, content, null, null, images);
    }

    public static AgentMessage assistant(String content, List<AgentToolCall> toolCalls) {
        return new AgentMessage(ROLE_ASSISTANT, content, null, toolCalls, List.of());
    }

    public static AgentMessage tool(String toolCallId, String content) {
        return new AgentMessage(ROLE_TOOL, content, toolCallId, null, List.of());
    }
}
