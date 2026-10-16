package com.mojian.agent.llm;

import java.util.Map;

/**
 * 模型上下文窗口对照表。
 *
 * <p><b>这里只放实测过的值，绝不猜测。</b>猜错的代价是双向的：
 * 猜大了会撞超限导致整轮失败，猜小了则白白浪费窗口、还误报"上下文快满了"。
 *
 * <p>为什么不靠"撞墙学习"就够了（{@code ContextLimitParser}）：那是个<b>死锁</b> ——
 * 预算被保守默认值卡住时，系统永远不会发出超限请求，于是永远学不到真实上限。
 * 所以需要一个正向来源：查表。
 *
 * <p>优先级（见 {@code AgentProfileService}）：
 * <ol>
 *   <li>数据库里存的值（学到的或手工配的）</li>
 *   <li>值为 0 = 未知 → 查本表</li>
 *   <li>表里没有 → {@link #FALLBACK}，并保留撞墙学习作为纠错</li>
 * </ol>
 */
public final class ModelContextWindow {

    /** 表里没有时的保守值。宁可先小后大，也不要先大后崩。 */
    public static final int FALLBACK = 131_072;

    /**
     * 已实测的模型上限。
     *
     * <p>{@code deepseek-v4.1-flash = 1_048_576}：
     * 2026-10-16 实测，发 200 万汉字被拒，错误信息明确回报
     * {@code This model's maximum context length is 1048576 tokens}。
     */
    private static final Map<String, Integer> MEASURED = Map.of(
            "deepseek-v4.1-flash", 1_048_576
    );

    private ModelContextWindow() {
    }

    /** 查表；查不到返回 {@link #FALLBACK} */
    public static int of(String model) {
        Integer limit = model == null ? null : MEASURED.get(model);
        return limit == null ? FALLBACK : limit;
    }

    public static boolean measured(String model) {
        return model != null && MEASURED.containsKey(model);
    }
}
