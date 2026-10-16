package com.mojian.agent;

import com.mojian.agent.dto.AgentChatRequest;
import com.mojian.agent.dto.AgentChatResponse;
import com.mojian.agent.tool.AgentTool;
import com.mojian.agent.tool.ToolRegistry;
import com.mojian.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 调试入口。
 *
 * <p>为什么先做成一个"调试接口"而不是直接塞进前端页面：
 * Agent 的行为是不确定的，先在最小的界面上把它跑通、看稳，
 * 再接进真实业务流程，出问题时排查范围小得多。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentLoopService agentLoopService;
    private final ToolRegistry toolRegistry;

    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@Valid @RequestBody AgentChatRequest request) {
        // 每次请求 = 一次独立的 Agent 运行，因此分配一个新的会话 id（利于服务端做 prompt 缓存）
        AgentContext context = AgentContext.of(request.projectId());
        AgentResult result = agentLoopService.run(context, AgentPrompts.CREATION_ASSISTANT, request.message());
        return ApiResponse.ok(AgentChatResponse.from(result));
    }

    /** 查看当前注册了哪些工具、它们的说明书长什么样（排查"模型为什么不调某个工具"的第一站） */
    @GetMapping("/tools")
    public ApiResponse<List<Map<String, String>>> tools() {
        return ApiResponse.ok(toolRegistry.all().stream()
                .map(tool -> Map.of("name", tool.name(), "description", tool.description()))
                .toList());
    }
}
