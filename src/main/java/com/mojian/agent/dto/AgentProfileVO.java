package com.mojian.agent.dto;

import java.math.BigDecimal;

/**
 * Agent 角色配置的对外视图 —— <b>密钥只出掩码，完整值不出后端</b>
 * （与墨笺设置页的安全约束一致：SRS 7.1）。
 *
 * @param baseUrlSource   接口地址来自哪里：{@code PROFILE}（本角色单独配的）
 *                        或 {@code GLOBAL}（沿用设置页 / 环境变量）
 * @param contextLimit    0 表示未知，会按实测对照表推断
 */
public record AgentProfileVO(String role, String model, BigDecimal temperature, int maxSteps,
                             int contextLimit, int reserveOutputTokens,
                             String baseUrl, String baseUrlSource,
                             boolean apiKeyConfigured, String apiKeyMask) {
}
