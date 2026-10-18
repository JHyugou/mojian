package com.mojian.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 策划对话请求。
 *
 * @param projectId      在哪个项目里策划
 * @param conversationId 继续哪一段对话；为 null 表示新建一段
 * @param message        用户这次说的话
 * @param runId          本次运行的标识，由前端生成。
 *                       <b>用途只有一个</b>：让"停止"能找到后端正在跑的这一次。
 *                       新开对话时会话 id 要等后端返回才知道，前端没法用它叫停，
 *                       所以需要一个前端自己就能生成的临时标识。可以不传。
 * @param images         本轮图片附件（data URL，最多 4 张）。只发给支持视觉的模型，
 *                       不进历史库 —— 历史只存文本，避免每轮重复上传几 MB 图片。
 */
public record PlannerChatRequest(
        @NotNull(message = "缺少 projectId") Long projectId,
        Long conversationId,
        @NotBlank(message = "内容不能为空") String message,
        String runId,
        List<String> images) {
}