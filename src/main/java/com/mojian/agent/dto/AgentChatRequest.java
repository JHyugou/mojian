package com.mojian.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Agent 调试入口的请求。
 *
 * @param projectId 在哪个项目里干活 —— 由调用方（前端）提供，最终注入到 AgentContext，
 *                  绝不经过模型
 * @param message   用户的自然语言问题
 */
public record AgentChatRequest(
        @NotNull(message = "缺少 projectId") Long projectId,
        @NotBlank(message = "问题不能为空") String message) {
}
