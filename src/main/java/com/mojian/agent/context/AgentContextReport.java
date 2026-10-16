package com.mojian.agent.context;

import java.util.List;

/**
 * 组装报表 —— 记录"这次装了什么、裁了什么、用了多少预算"。
 *
 * <p>这份报表是 Agent 可观测性的一部分：
 * <ul>
 *   <li>界面上可以展示"本次上下文用掉 12 万 / 可用 94 万 token，裁掉 3 章原文"</li>
 *   <li>阶段 6 做"全量 vs 截断"对比实验时，靠它才知道实验组到底装了什么</li>
 *   <li>排查"模型为什么忘了前文"时，第一眼看的就是这里</li>
 * </ul>
 */
public record AgentContextReport(int budgetTokens, int usedTokens,
                                 List<String> keptTitles, List<String> trimmedTitles) {

    public AgentContextReport {
        keptTitles = List.copyOf(keptTitles);
        trimmedTitles = List.copyOf(trimmedTitles);
    }

    public double usageRatio() {
        return budgetTokens == 0 ? 0d : (double) usedTokens / budgetTokens;
    }

    public boolean hasTrimmed() {
        return !trimmedTitles.isEmpty();
    }

    /** 给人看的一句话摘要 */
    public String summary() {
        String base = String.format("上下文用量 %d / %d token（%.0f%%），装入 %d 段",
                usedTokens, budgetTokens, usageRatio() * 100, keptTitles.size());
        return hasTrimmed() ? base + "，裁掉 " + trimmedTitles.size() + " 段：" + trimmedTitles : base;
    }
}
