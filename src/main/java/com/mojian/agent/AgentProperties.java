package com.mojian.agent;

/**
 * Agent 循环的参数。
 *
 * <p>故意先做成普通 POJO（不加 Spring 注解），等接入 Spring 时再补
 * {@code @ConfigurationProperties(prefix = "mojian.agent")}。
 * 这样核心逻辑可以先独立测透，不被框架牵着走。
 */
public class AgentProperties {

    /**
     * 最大步数 —— 第一道刹车。
     *
     * <p>为什么必须有：模型可能陷入"调工具 → 不满意 → 再调同一个工具"的循环，
     * 没人拦它就是一直烧钱、永远不结束。
     */
    private int maxSteps = 10;

    public int getMaxSteps() {
        return maxSteps;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }
}
