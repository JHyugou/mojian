package com.mojian.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 策划对话请求。
 *
 * @param projectId      在哪个项目里策划
 * @param conversationId 继续哪一段对话；为 null 表示新建一段
 * @param message        用户这次说的话
 */
public record PlannerChatRequest(
        @NotNull(message = "缺少 projectId") Long projectId,
        Long conversationId,
        @NotBlank(message = "内容不能为空") String message) {
}
