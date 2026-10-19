package com.mojian.agent.dto;

import java.math.BigDecimal;

/**
 * Agent 角色配置的对外视图 —— <b>密钥只出掩码，完整值不出后端</b>
 * （与墨笺设置页的安全约束一致：SRS 7.1）。
 *
 * @param providerId      选中的服务商 id；null 表示旧全局/角色连接
 * @param providerName    选中的服务商显示名
 * @param baseUrlSource   接口地址来自哪里：{@code PROVIDER} / {@code PROFILE} / {@code GLOBAL}
 * @param apiKeySource    Key 来自哪里：{@code PROVIDER} / {@code PROFILE} / {@code GLOBAL} / {@code NONE}
 * @param contextLimit    0 表示未知，会按实测对照表推断
 * @param reasoningEffort 思考强度；null 表示不传、用服务商默认
 */
public record AgentProfileVO(String role, String model, Long providerId, String providerName,
                             BigDecimal temperature, String reasoningEffort, int maxSteps,
                             int contextLimit, int reserveOutputTokens,
                             String baseUrl, String baseUrlSource,
                             boolean apiKeyConfigured, String apiKeyMask,
                             String apiKeySource) {
}
