package com.mojian.agent.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 模型单价表 —— 用来估算花销，<b>不是账单</b>。
 *
 * <p>数据来源：OpenCode Go 定价页（2026-10-16）。只收录了会用到的模型；
 * 查不到的模型按 0 计，界面上仍能看到 token 数。
 *
 * <p>注意 DeepSeek 系列有峰谷价（峰时 01:00-04:00、06:00-10:00 UTC 工作日），
 * 这里统一按<b>谷时</b>计，所以实际账单只会比估算低或接近。
 */
public final class ModelPricing {

    /** 每 100 万 token 的美元单价 */
    public record Price(double input, double cachedRead, double output) {
    }

    private static final Map<String, Price> PRICES = Map.of(
            "deepseek-v4.1-flash", new Price(0.15, 0.003, 0.60),
            "deepseek-v4-pro", new Price(0.66, 0.022, 1.98),
            "qwen3.8-max", new Price(2.00, 0.25, 6.00),
            "qwen3.8-flash", new Price(0.15, 0.016, 0.47),
            "glm-5.3-flash", new Price(0.15, 0.03, 0.50),
            "glm-5.3", new Price(1.40, 0.26, 4.40),
            "kimi-k3", new Price(3.00, 0.30, 15.00),
            "minimax-m3", new Price(0.30, 0.06, 1.20)
    );

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    /**
     * 未知模型的兜底单价（按常见中端模型估）。
     *
     * <p>之前查不到就直接记 0：换一家供应商之后，界面上永远显示"本次花费 $0"，
     * 比没有数字更糟 —— 它会让人以为不要钱。宁可偏高一点，也不要假装免费。
     */
    private static final Price FALLBACK = new Price(0.15, 0.003, 0.60);

    private ModelPricing() {
    }

    /**
     * 估算一次调用的费用（美元）。
     *
     * @param promptTokens     OpenAI 语义的输入总量（<b>含</b>命中缓存的部分）
     * @param cachedTokens     其中命中 prompt 缓存的部分（单价低几十分之一）
     * @param completionTokens 输出量
     */
    public static BigDecimal estimateCost(String model, int promptTokens, int cachedTokens, int completionTokens) {
        Price price = PRICES.get(model);
        if (price == null) {
            price = FALLBACK;
        }
        int cached = Math.max(0, Math.min(cachedTokens, promptTokens));
        int uncached = Math.max(0, promptTokens - cached);
        // 输出也要夹紧：服务端返回的 usage 万一带负数，费用会算成负数，
        // 累计到"本月已用"里就会凭空抵消真实花销
        int completion = Math.max(0, completionTokens);

        BigDecimal cost = BigDecimal.valueOf(uncached).multiply(BigDecimal.valueOf(price.input()))
                .add(BigDecimal.valueOf(cached).multiply(BigDecimal.valueOf(price.cachedRead())))
                .add(BigDecimal.valueOf(completion).multiply(BigDecimal.valueOf(price.output())))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        return cost.max(BigDecimal.ZERO);
    }

    public static boolean known(String model) {
        return PRICES.containsKey(model);
    }
}
