package com.mojian.agent;

import com.mojian.agent.llm.TokenUsage;

import java.util.List;

/**
 * Agent 跑完一轮的结果。
 *
 * <p>status 是刻意设计的：<b>停下来不等于成功</b>。
 * 这两种出口对用户要说完全不同的话，混成一个"null 或空字符串"就没法处理了。
 *
 * @param usage 这次运行累计消耗的 token（各轮相加）。落进用量流水表后，
 *              "这次运行花了多少"就是可查的数字，而不是感觉
 */
public record AgentResult(Status status, String answer, List<AgentStep> trace, int steps, TokenUsage usage) {

    public enum Status {
        /** 正常出口：模型不再要求调工具，给出了回答 */
        FINISHED,
        /** 异常出口：转到最大步数被刹住 */
        MAX_STEPS
    }

    public AgentResult {
        trace = List.copyOf(trace);
        usage = usage == null ? TokenUsage.NONE : usage;
    }

    public static AgentResult finished(String answer, List<AgentStep> trace, int steps, TokenUsage usage) {
        return new AgentResult(Status.FINISHED, answer, trace, steps, usage);
    }

    /**
     * 被刹车时的兜底话术 —— 注意这里不是返回空，而是<b>告诉用户发生了什么、并给下一步建议</b>。
     * 一个闷头转到上限然后吐空白的 Agent，和一个会说"我试了 X 步，要不要换个说法"的 Agent，
     * 技术实现差不多，用户感受差十倍。
     */
    public static AgentResult maxSteps(List<AgentStep> trace, int steps, TokenUsage usage) {
        return new AgentResult(Status.MAX_STEPS,
                "我尝试了 " + steps + " 步仍然没有得出结论，先停下来。"
                        + "你可以把要求说得更具体一些，或者换个问法。",
                trace, steps, usage);
    }

    public boolean succeeded() {
        return status == Status.FINISHED;
    }
}
