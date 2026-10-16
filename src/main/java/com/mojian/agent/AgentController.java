package com.mojian.agent;

import com.mojian.agent.dto.AgentChatRequest;
import com.mojian.agent.dto.AgentChatResponse;
import com.mojian.agent.dto.AgentProfileUpdateRequest;
import com.mojian.agent.dto.AgentProfileVO;
import com.mojian.agent.dto.PlannerChatRequest;
import com.mojian.agent.dto.PlannerChatResponse;
import com.mojian.agent.context.AgentContextAssembly;
import com.mojian.agent.context.AgentContextBudget;
import com.mojian.agent.entity.AgentPlanOption;
import com.mojian.agent.service.AgentContextService;
import com.mojian.agent.service.AgentProfileService;
import com.mojian.agent.service.ConversationService;
import com.mojian.agent.service.PlannerService;
import com.mojian.agent.tool.AgentTool;
import com.mojian.agent.tool.ToolRegistry;
import com.mojian.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 交互接口。
 *
 * <p>分成两组：
 * <ul>
 *   <li>{@code /planner/**} —— 策划对话（真正给用户用的）</li>
 *   <li>其余 —— 调试与可观测性（工具清单、上下文用量、方案列表）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private final AgentLoopService agentLoopService;
    private final ToolRegistry toolRegistry;
    private final AgentContextService agentContextService;
    private final PlannerService plannerService;
    private final ConversationService conversationService;
    private final AgentProfileService profileService;

    // ==================== 策划对话 ====================

    /**
     * 与策划 Agent 对话。
     *
     * <p>{@code conversationId} 传 null 就新开一段；传回来就是继续那段。
     */
    @PostMapping("/planner/chat")
    public ApiResponse<PlannerChatResponse> plannerChat(@Valid @RequestBody PlannerChatRequest request) {
        return ApiResponse.ok(plannerService.chat(
                request.projectId(), request.conversationId(), request.message()));
    }

    /** 该会话的剧情方案（前端渲染成方案卡） */
    @GetMapping("/conversations/{conversationId}/plan-options")
    public ApiResponse<List<AgentPlanOption>> planOptions(@PathVariable Long conversationId) {
        return ApiResponse.ok(conversationService.listPlanOptions(conversationId));
    }

    /** 用户选定某条走向；选定后其余自动取消，且它不再作为"待选方案"注入下一轮 */
    @PostMapping("/conversations/{conversationId}/plan-options/{optionIndex}/select")
    public ApiResponse<AgentPlanOption> selectPlanOption(@PathVariable Long conversationId,
                                                          @PathVariable int optionIndex) {
        return ApiResponse.ok(conversationService.selectOption(conversationId, optionIndex));
    }

    // ==================== 角色配置（设置页） ====================

    /**
     * 主 / 副 Agent 各自的模型配置。
     *
     * <p>为什么"每个角色一套"是必需的而不是锦上添花：
     * 策划要稳、要便宜、要能一口气读完几十万字设定；写作要发散、要文笔。
     * 一个模型服务两边是妥协，不是设计。
     */
    @GetMapping("/profiles")
    public ApiResponse<List<AgentProfileVO>> profiles() {
        return ApiResponse.ok(profileService.list().stream().map(profileService::toVo).toList());
    }

    /** 更新某个角色的模型配置（密钥只出掩码；空串表示清除覆盖、回到全局设置） */
    @PutMapping("/profiles/{role}")
    public ApiResponse<AgentProfileVO> updateProfile(@PathVariable String role,
                                                     @RequestBody AgentProfileUpdateRequest request) {
        return ApiResponse.ok(profileService.toVo(
                profileService.update(role.toUpperCase(), request)));
    }

    // ==================== 调试与可观测性 ====================

    /** 最简调试对话（不注入材料，只验证工具链路还通不通） */
    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@Valid @RequestBody AgentChatRequest request) {
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
     * 报表里有"装了什么、裁了什么、用了多少"，排查"Agent 为什么忘了前文"时先看这里。
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
