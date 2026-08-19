package com.mojian.generation.llm;

/**
 * 模型请求。purpose 仅用于 Mock 客户端区分生成类型与日志诊断，
 * 真实客户端不发送该字段。
 */
public record LlmRequest(String systemPrompt, String userPrompt, String purpose) {

    public static LlmRequest of(String systemPrompt, String userPrompt, String purpose) {
        return new LlmRequest(systemPrompt, userPrompt, purpose);
    }
}
