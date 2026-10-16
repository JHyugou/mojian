package com.mojian.agent;

import com.mojian.agent.dto.AgentChatRequest;
import com.mojian.agent.dto.AgentChatResponse;
import com.mojian.agent.context.AgentContextAssembly;
import com.mojian.agent.context.AgentContextBudget;
import com.mojian.agent.service.AgentContextService;
import com.mojian.agent.tool.AgentTool;
import com.mojian.agent.tool.ToolRegistry;
import com.mojian.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final AgentContextService agentContextService;

    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@Valid @RequestBody AgentChatRequest request) {
        // 每次请求 = 一次独立的 Agent 运行，因此分配一个新的会话 id（利于服务端做 prompt 缓存）。
        // 这个调试入口走策划角色；写作角色的入口在阶段 3 接上 generation_task 之后再加。
        AgentContext context = AgentContext.of(request.projectId(), AgentRole.PLANNER);
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

    /**
     * 上下文装填情况 —— 界面上的"上下文用量 xx%"就用它。
     *
     * <p>只返回报表不返回正文：材料正文可能几万字，调试接口不该把它整个吐回浏览器。
     * 报表里有"装了什么、裁了什么、用了多少"，排查" Agent 为什么忘了前文"时先看这里。
     */
    @GetMapping("/projects/{projectId}/context-report")
    public ApiResponse<Map<String, Object>> contextReport(@PathVariable Long projectId,
                                                          @RequestParam(defaultValue = "PLANNER") String role) {
        AgentContextAssembly assembly = agentContextService.assemble(role, projectId);
        AgentContextBudget budget = agentContextService.budgetOf(role);

        return ApiResponse.ok(Map.of(
                "role", role,
                "contextLimit", budget.contextLimitTokens(),
                "usableTokens", budget.usableTokens(),
                "usedTokens", assembly.report().usedTokens(),
                "usagePercent", Math.round(assembly.report().usageRatio() * 1000) / 10.0,
                "keptSections", assembly.report().keptTitles(),
                "trimmedSections", assembly.report().trimmedTitles(),
                "summary", assembly.report().summary()));
    }
}
