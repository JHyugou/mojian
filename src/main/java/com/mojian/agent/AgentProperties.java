package com.mojian.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 循环参数（配置前缀 {@code mojian.agent}）。
 *
 * <p>参数外置的意义：不用改代码就能调整 Agent 的行为边界。
 * 出问题时第一反应往往是"把步数调小一点先止血"，这不该需要重新发版。
 */
@Data
@Component
@ConfigurationProperties(prefix = "mojian.agent")
public class AgentProperties {

    /**
     * 最大步数 —— 第一道刹车。
     *
     * <p>为什么必须有：模型可能陷入"调工具 → 不满意 → 再调同一个工具"的循环，
     * 没人拦它就是一直烧钱、永远不结束。
     */
    private int maxSteps = 10;
}
