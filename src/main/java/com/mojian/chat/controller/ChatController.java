package com.mojian.chat.controller;

import com.mojian.chat.ChatService;
import com.mojian.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI 创作助手聊天接口（FR-023）。
 * 无状态：前端管理对话历史，每次请求携带全部历史。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/projects/{projectId}/chat")
    public ApiResponse<Map<String, String>> chat(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> body) {
        String message = String.valueOf(body.getOrDefault("message", ""));
        @SuppressWarnings("unchecked")
        List<Map<String, String>> history =
                (List<Map<String, String>>) body.getOrDefault("history", List.of());
        String reply = chatService.chat(projectId, message, history);
        return ApiResponse.ok(Map.of("reply", reply));
    }
}
