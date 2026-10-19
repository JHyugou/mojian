package com.mojian.agent.dto;

import java.math.BigDecimal;

/**
 * 更新某个 Agent 角色的模型配置。
 *
 * <p>字段语义（沿用墨笺设置页的既有约定，避免两套规则）：
 * <ul>
 *   <li>{@code model / temperature / maxSteps / contextLimit / reasoningEffort}
 *       为 null 表示保持不变</li>
 *   <li>{@code baseUrl} 为 null 表示不变；空字符串表示清除覆盖（回到全局设置）</li>
 *   <li>{@code apiKey} 为 null 表示不变；空字符串表示清除；非空表示替换</li>
 * </ul>
 *
 * <p>为什么要有"清除覆盖"这个语义：角色配置里的接口地址与密钥是<b>可选的覆盖</b>，
 * 默认沿用 {@code llm_settings}。换供应商时，你可能只想用一次就切回来。
 *
 * @param providerId      选中的服务商 id；非 null 时使用该服务商的地址与 Key
 * @param reasoningEffort 思考强度（low / medium / high 等）。各家值域不同，
 *                        所以只存字符串不校验 —— 真正的可选值由模型目录提供给界面。
 *                        传空字符串表示"回到服务商默认"。
 */
public record AgentProfileUpdateRequest(
        String model,
        Long providerId,
        BigDecimal temperature,
        String reasoningEffort,
        Integer maxSteps,
        Integer contextLimit,
        String baseUrl,
        String apiKey) {
}
