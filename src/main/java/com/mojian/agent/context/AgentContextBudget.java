package com.mojian.agent.context;

/**
 * 这一次组装的预算。
 *
 * @param contextLimitTokens 模型真实的上下文上限（由 {@link ContextLimitParser} 从服务端错误里学到，
 *                           或来自配置的保守默认值）
 * @param reserveOutputTokens 给模型输出预留的 token（一边写一边算，输出也算在窗口里）
 */
public record AgentContextBudget(int contextLimitTokens, int reserveOutputTokens) {

    /**
     * 安全系数：只使用上限的 90%。
     *
     * <p>为什么留 10%：token 估算终究是估算，而且系统提示词里的规则、工具清单
     * （{@code tools} 字段也会占 token，但本估算器看不到）都会额外消耗。
     * 留余量比撞上限后整轮失败划算得多。
     */
    public static final double SAFETY_RATIO = 0.9;

    public static final int DEFAULT_CONTEXT_LIMIT = 131_072;

    public AgentContextBudget {
        if (contextLimitTokens <= 0) {
            throw new IllegalArgumentException("上下文上限必须为正数: " + contextLimitTokens);
        }
        if (reserveOutputTokens < 0) {
            throw new IllegalArgumentException("预留输出不能为负: " + reserveOutputTokens);
        }
    }

    /** 保守默认值：128K 上限、预留 8K 输出 */
    public static AgentContextBudget conservative() {
        return new AgentContextBudget(DEFAULT_CONTEXT_LIMIT, 8_192);
    }

    public static AgentContextBudget of(int contextLimitTokens, int reserveOutputTokens) {
        return new AgentContextBudget(contextLimitTokens, reserveOutputTokens);
    }

    /** 实际可用于装填材料的 token 数 */
    public int usableTokens() {
        return Math.max(0, (int) (contextLimitTokens * SAFETY_RATIO) - reserveOutputTokens);
    }
}
