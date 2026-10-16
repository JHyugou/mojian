package com.mojian.agent.llm;

/**
 * 一次模型调用消耗的 token。
 *
 * <p>为什么要认真记：额度和钱都是硬约束，而"这次调用花了多少"只有服务端知道。
 * 拿到之后落到 {@code agent_usage_log}，界面上才能显示实时消费。
 *
 * @param promptTokens    输入总量（OpenAI 语义：<b>含</b>命中缓存的部分）
 * @param cachedTokens    其中命中 prompt 缓存的部分 —— 单价通常只有未命中的几十分之一，
 *                        所以一次调用的真实成本主要由这个数字决定
 * @param completionTokens 输出量
 */
public record TokenUsage(int promptTokens, int cachedTokens, int completionTokens) {

    public static final TokenUsage NONE = new TokenUsage(0, 0, 0);

    public TokenUsage {
        promptTokens = Math.max(0, promptTokens);
        cachedTokens = Math.max(0, cachedTokens);
        completionTokens = Math.max(0, completionTokens);
    }

    public TokenUsage plus(TokenUsage other) {
        if (other == null) {
            return this;
        }
        return new TokenUsage(promptTokens + other.promptTokens,
                cachedTokens + other.cachedTokens,
                completionTokens + other.completionTokens);
    }

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public String describe() {
        return String.format("输入 %d（缓存 %d）/ 输出 %d", promptTokens, cachedTokens, completionTokens);
    }
}
