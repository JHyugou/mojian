package com.mojian.agent.context;

/**
 * token 估算。
 *
 * <p><b>实测依据</b>（2026-10-16，deepseek-v4.1-flash）：
 * 发送 200 万汉字，服务端回报 {@code 2000030 tokens} —— 即 <b>1 个汉字 ≈ 1 token</b>。
 * 所以直接按字符数估算：对中文准确，对英文/代码会高估。
 *
 * <p>高估是<b>安全</b>的：宁可早一点裁剪，也不要因为低估而撞上服务端的上下文上限。
 * 另外组装器还会再乘一个安全系数（见 {@link AgentContextBudget}），双重保险。
 */
public final class AgentTokenEstimator {

    /** 截断时追加的提示语，本身也占 token */
    public static final String TRUNCATE_MARKER = "\n……（内容过长，此处已截断）";

    private AgentTokenEstimator() {
    }

    public static int estimate(String text) {
        return text == null ? 0 : text.length();
    }
}
