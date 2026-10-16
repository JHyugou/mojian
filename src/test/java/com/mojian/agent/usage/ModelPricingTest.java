package com.mojian.agent.usage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 费用估算测试。
 *
 * <p>为什么值得测：额度是硬上限，估算写错会导致"以为还很宽裕，其实已经用掉一半"。
 * 这里用能心算的数字把手算结果钉死。
 */
class ModelPricingTest {

    private static final String FLASH = "deepseek-v4.1-flash";
    private static final String QWEN = "qwen3.8-max";

    @Test
    @DisplayName("全部未命中缓存：按输入单价计（100 万 token = $0.15）")
    void 未命中缓存按输入单价计() {
        BigDecimal cost = ModelPricing.estimateCost(FLASH, 1_000_000, 0, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.150000"));
    }

    @Test
    @DisplayName("全部命中缓存：单价降到 1/50（100 万 token = $0.003）")
    void 命中缓存单价极低() {
        BigDecimal cost = ModelPricing.estimateCost(FLASH, 1_000_000, 1_000_000, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.003000"));
    }

    @Test
    @DisplayName("混合场景手算：10 万输入（未命中）+ 1 万输出 = $0.021")
    void 混合场景手算正确() {
        BigDecimal cost = ModelPricing.estimateCost(FLASH, 100_000, 0, 10_000);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.021000"));
    }

    @Test
    @DisplayName("缓存部分从输入里扣除，不重复计费")
    void 缓存部分不重复计费() {
        // 10 万输入，其中 6 万命中：4 万 × 0.15 + 6 万 × 0.003 = 0.006 + 0.00018 = 0.00618
        BigDecimal cost = ModelPricing.estimateCost(FLASH, 100_000, 60_000, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.006180"));
    }

    @Test
    @DisplayName("qwen3.8-max 同样用量贵一个数量级（这就是选 flash 的理由）")
    void 贵模型成本高出数量级() {
        BigDecimal flash = ModelPricing.estimateCost(FLASH, 100_000, 0, 10_000);
        BigDecimal qwen = ModelPricing.estimateCost(QWEN, 100_000, 0, 10_000);

        assertThat(qwen).isGreaterThan(flash.multiply(BigDecimal.TEN));
    }

    @Test
    @DisplayName("未知模型不报错，返回 0（单价表里没有的模型仍能记录 token 数）")
    void 未知模型返回零() {
        assertThat(ModelPricing.estimateCost("some-unknown-model", 1_000_000, 0, 1_000_000))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(ModelPricing.known(FLASH)).isTrue();
        assertThat(ModelPricing.known("some-unknown-model")).isFalse();
    }

    @Test
    @DisplayName("缓存数超过输入数这类脏数据不会算出负数或超额费用")
    void 脏数据防御() {
        // cached(20万) > prompt(10万)：按 10 万命中计，未命中按 0 计
        BigDecimal cost = ModelPricing.estimateCost(FLASH, 100_000, 200_000, 0);
        assertThat(cost).isEqualByComparingTo(new BigDecimal("0.000300"));

        assertThat(ModelPricing.estimateCost(FLASH, -1, -1, -1))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}
