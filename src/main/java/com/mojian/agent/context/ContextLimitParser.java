package com.mojian.agent.context;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从服务端的超限错误里，把模型真实的上下文上限<b>学出来</b>。
 *
 * <p>为什么需要它：各家的上下文上限差别很大（128K / 256K / 1M），而模型列表接口
 * 通常不提供这个信息。好在服务商拒绝超限请求时，会把上限直接写在错误里：
 *
 * <pre>
 * This model's maximum context length is 1048576 tokens. However, you requested 2000046 tokens ...
 * </pre>
 *
 * <p>于是我们不需要硬编码，也不需要维护一张模型能力表：<b>撞一次墙，就学会一次</b>。
 * 学到之后写进 {@code agent_profile.context_limit}，下次组装就按真实上限算预算。
 * 用户随时在设置页换模型也不会崩 —— 大不了再学一次。
 *
 * <p>实测（2026-10-16）：超限请求不产生费用，所以"学"的成本接近于零。
 */
public final class ContextLimitParser {

    /**
     * 用 {@code \s+} 而不是空格：服务商可能把错误 JSON 格式化成多行，
     * 那样 "maximum context" 和 "length" 之间会夹着换行。
     * （这个坑是被一条失败的单元测试抓出来的，不是猜的。）
     */
    private static final Pattern MAX_CONTEXT = Pattern.compile(
            "maximum\\s+context\\s+length\\s+is\\s+(\\d+)\\s*tokens", Pattern.CASE_INSENSITIVE);

    private ContextLimitParser() {
    }

    /**
     * @param errorText 服务端返回的错误文本（整个 body 直接传进来即可）
     * @return 解析出的上下文上限；信息里没有就返回空（调用方保持原值不变）
     */
    public static OptionalInt parse(String errorText) {
        if (errorText == null || errorText.isBlank()) {
            return OptionalInt.empty();
        }
        Matcher matcher = MAX_CONTEXT.matcher(errorText);
        if (!matcher.find()) {
            return OptionalInt.empty();
        }
        try {
            int limit = Integer.parseInt(matcher.group(1));
            return limit > 0 ? OptionalInt.of(limit) : OptionalInt.empty();
        } catch (NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
