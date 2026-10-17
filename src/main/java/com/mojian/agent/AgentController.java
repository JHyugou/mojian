package com.mojian.agent;

import com.mojian.agent.dto.AgentChatRequest;
import com.mojian.agent.dto.AgentChatResponse;
import com.mojian.agent.dto.AgentProfileUpdateRequest;
import com.mojian.agent.dto.AgentProfileVO;
import com.mojian.agent.dto.PlannerChatRequest;
import com.mojian.agent.dto.PlannerChatResponse;
import com.mojian.agent.dto.WriterRunRequest;
import com.mojian.agent.dto.WritingRequirementRequest;
import com.mojian.agent.context.AgentContextAssembly;
import com.mojian.agent.context.AgentContextBudget;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.entity.AgentDraft;
import com.mojian.agent.entity.AgentPlanOption;
import com.mojian.agent.service.AgentContextService;
import com.mojian.agent.service.AgentProfileService;
import com.mojian.agent.service.AgentUsageService;
import com.mojian.agent.service.ConversationService;
import com.mojian.agent.service.DraftService;
import com.mojian.agent.service.PlannerService;
import com.mojian.agent.service.WriterService;
import com.mojian.agent.tool.AgentTool;
import com.mojian.agent.tool.ToolRegistry;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import com.mojian.common.api.ApiResponse;
import com.mojian.generation.sse.TaskEventBroker;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
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
@Slf4j
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
    private final DraftService draftService;
    private final WriterService writerService;
    private final ChapterService chapterService;
    private final AgentUsageService usageService;
    private final TaskEventBroker broker;

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

    // ==================== 写作 ====================

    /**
     * 本书的写作会话与历史。
     *
     * <p>写作会话是<b>整本书一个、跨章存续</b>的 —— 副 Agent 靠它记得住
     * 你此前说过的写作偏好与约束，而不是每章从零开始。
     */
    @GetMapping("/writer/projects/{projectId}/conversation")
    public ApiResponse<Map<String, Object>> writerConversation(@PathVariable Long projectId) {
        AgentConversation conversation = conversationService.findOrCreateWriter(projectId);
        List<Map<String, String>> messages = conversationService
                .loadTextHistory(conversation.getId())
                .stream()
                .map(m -> Map.of("role", m.role(),
                        "content", m.content() == null ? "" : m.content()))
                .toList();
        return ApiResponse.ok(Map.of(
                "conversationId", conversation.getId(),
                "messages", messages));
    }

    /**
     * 清空写作会话的历史（保留会话本身）。
     *
     * <p>长期记忆是优点，但作者需要能重置它 —— 比如大方向改了、
     * 或早先说过某条偏好不再适用。
     */
    @DeleteMapping("/writer/projects/{projectId}/conversation/messages")
    public ApiResponse<Void> clearWriterHistory(@PathVariable Long projectId) {
        AgentConversation conversation = conversationService.findOrCreateWriter(projectId);
        conversationService.clearMessages(conversation.getId());
        return ApiResponse.ok();
    }

    /**
     * 启动一次写作任务，<b>立刻返回任务 id</b>。
     *
     * <p>写一章要两三分钟，同步接口会让前端干等、中途刷新还断掉。
     * 所以拆成"启动任务 → 订阅事件流"两段。
     */
    @PostMapping("/writer/chapters/{chapterId}/tasks")
    public ApiResponse<Map<String, Object>> startWriting(@PathVariable Long chapterId,
                                                         @RequestBody(required = false) WriterRunRequest request) {
        String instruction = request == null ? null : request.instruction();
        Long taskId = writerService.startTask(chapterId, instruction);
        return ApiResponse.ok(Map.of("taskId", taskId));
    }

    /**
     * 订阅写作任务的事件流（复用墨笺既有的 {@code TaskEventBroker}）。
     *
     * <p>事件类型：
     * <ul>
     *   <li>{@code status} —— RUNNING / SUCCEEDED / FAILED；订阅时会立即回放当前状态</li>
     *   <li>{@code delta} —— <b>正文增量</b>。订阅时也会回放已写好的部分，所以刷新页面不丢字</li>
     *   <li>{@code reasoning} —— 思维链增量</li>
     *   <li>{@code progress} / {@code tool-call} / {@code tool-result} —— 过程进展</li>
     *   <li>{@code result} / {@code end} —— 结果摘要与结束</li>
     * </ul>
     */
    @GetMapping(value = "/writer/tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter writingEvents(@PathVariable Long taskId) {
        return broker.subscribe(taskId);
    }

    /**
     * 同步写作（调试用，会一直阻塞到写完，约两三分钟）。
     *
     * <p>保留了它是因为排查问题时"一次调用看完整结果"比"订阅事件流"省事得多。
     */
    @PostMapping("/writer/chapters/{chapterId}/write")
    public ApiResponse<WriterService.WriterResult> write(@PathVariable Long chapterId,
                                                         @RequestBody(required = false) WriterRunRequest request) {
        String instruction = request == null ? null : request.instruction();
        return ApiResponse.ok(writerService.write(chapterId, instruction));
    }

    /**
     * 设置本章写作要求。
     *
     * <p>单独一个端点，不并进章节保存接口：后者是全量属性覆盖，
     * 加一个可空字段会在前端提交其他字段时把写作要求静默清空。
     */
    @PutMapping("/chapters/{chapterId}/writing-requirement")
    public ApiResponse<Chapter> updateWritingRequirement(@PathVariable Long chapterId,
                                                         @RequestBody WritingRequirementRequest request) {
        return ApiResponse.ok(chapterService.updateWritingRequirement(chapterId, request.requirement()));
    }

    // ==================== 会话（项目与对话解耦） ====================

    /**
     * 会话列表。
     *
     * <p><b>不传 projectId 就返回全部</b> —— 左侧的工作区树是"所有项目 + 各自的会话"，
     * 按项目逐个请求既慢又要等 N 次往返。
     *
     * @param projectId 可选：只看某个项目的
     * @param role      可选：只看策划或只看写作
     */
    @GetMapping("/conversations")
    public ApiResponse<List<Map<String, Object>>> conversations(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String role) {
        List<AgentConversation> all = projectId == null
                ? conversationService.listAll(role)
                : conversationService.list(projectId, role);
        return ApiResponse.ok(all.stream()
                .map(c -> Map.<String, Object>of(
                        "id", c.getId(),
                        "projectId", c.getProjectId() == null ? 0L : c.getProjectId(),
                        "role", c.getAgentRole() == null ? "" : c.getAgentRole(),
                        "title", c.getTitle() == null ? "未命名对话" : c.getTitle(),
                        "updatedAt", c.getUpdatedAt() == null ? "" : c.getUpdatedAt().toString()))
                .toList());
    }

    /** 给会话改名。标题原本是从首条消息里截出来的，往往不像人话 */
    @PutMapping("/conversations/{conversationId}/title")
    public ApiResponse<Map<String, Object>> renameConversation(@PathVariable Long conversationId,
                                                               @RequestBody Map<String, String> body) {
        AgentConversation conversation = conversationService.rename(conversationId, body.get("title"));
        return ApiResponse.ok(Map.of(
                "id", conversation.getId(),
                "title", conversation.getTitle() == null ? "" : conversation.getTitle()));
    }

    /** 删除一段会话（连同它的消息）—— 工作区树不能只增不减 */
    @DeleteMapping("/conversations/{conversationId}")
    public ApiResponse<Void> deleteConversation(@PathVariable Long conversationId) {
        conversationService.delete(conversationId);
        return ApiResponse.ok();
    }

    /** 一段会话的"人话"历史（用户的指令 + 助手的回答），用于界面上回看 */
    @GetMapping("/conversations/{conversationId}/messages")
    public ApiResponse<List<Map<String, String>>> conversationMessages(@PathVariable Long conversationId) {
        conversationService.require(conversationId);
        return ApiResponse.ok(conversationService.loadTextHistory(conversationId).stream()
                .map(m -> Map.of("role", m.role(),
                        "content", m.content() == null ? "" : m.content()))
                .toList());
    }

    // ==================== 草稿（人工确认） ====================

    /**
     * 用量与花销（<b>单位是美元</b>，单价表按 OpenCode Go 的美元定价）。
     *
     * <p>为什么值得有个接口：Agent 的花销是<b>不可见</b>的 ——
     * 它不像点外卖那样有个明确的"提交"动作，而是在对话里一次次调用悄悄累积。
     * 没有这个数字，作者能感知到的只有两种状态："好像还行"和"怎么欠费了"。
     */
    @GetMapping("/usage")
    public ApiResponse<Map<String, Object>> usage(@RequestParam(defaultValue = "20") int limit,
                                                  @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(Map.of(
                "currency", "USD",
                "recent", usageService.recent(limit),
                "summary", usageService.summary(days)));
    }

    /**
     * 前端把未捕获的错误回传到这里。
     *
     * <p>为什么值得有一个：前端出错时，用户能告诉我们的只有"页面白了" ——
     * 而真正的错误在浏览器的控制台里，我们看不到。让页面自己把错误报回来，
     * 排查就从"猜"变成了"看"。
     *
     * <p>报告落到服务端日志，和请求日志放在一起，时间点能对上。
     */
    @PostMapping("/client-error")
    public ApiResponse<Void> clientError(@RequestBody Map<String, String> body) {
        log.error("[CLIENT-ERROR] {} @ {}",
                body.getOrDefault("message", "(无消息)"),
                body.getOrDefault("where", "(未知位置)"));
        return ApiResponse.ok();
    }

    /**
     * 终止一个正在跑的写作任务。
     *
     * <p>光让前端断开 SSE 是不够的 —— 后端线程还在跑、模型还在生成、钱还在花。
     * 用户按下终止的意思是"现在停"，不是"我看不见了"。
     */
    @PostMapping("/writer/tasks/{taskId}/cancel")
    public ApiResponse<Map<String, Object>> cancelWriting(@PathVariable Long taskId) {
        return ApiResponse.ok(Map.of("cancelled", writerService.cancel(taskId)));
    }

    /**
     * 批量采纳本书所有待确认草稿。
     *
     * <p>为什么循环写在控制器而不是 service 里：{@code draftService.accept} 上标了
     * {@code @Transactional}，但<b>同类内部调用不经过代理、事务注解不生效</b>。
     * 放在这里逐条调用，每条才是独立事务 —— 一条失败不会把另外七条一起回滚。
     */
    @PostMapping("/projects/{projectId}/drafts/accept-all")
    public ApiResponse<Map<String, Object>> acceptAllDrafts(@PathVariable Long projectId) {
        List<AgentDraft> pending = draftService.listPendingByProject(projectId);
        int accepted = 0;
        List<String> failed = new ArrayList<>();
        for (AgentDraft draft : pending) {
            try {
                draftService.accept(draft.getId());
                accepted++;
            } catch (Exception e) {
                failed.add(draft.getTitle());
                log.warn("批量采纳：草稿 {}「{}」失败（{}）",
                        draft.getId(), draft.getTitle(), e.getMessage());
            }
        }
        log.info("批量采纳（项目 {}）：{} / {} 条成功", projectId, accepted, pending.size());
        return ApiResponse.ok(Map.of(
                "total", pending.size(),
                "accepted", accepted,
                "failed", failed));
    }

    /**
     * 本书的草稿。
     *
     * <p><b>按项目查，不按会话查</b>：写作 Agent 也会起草人物卡（写到一半发现缺角色），
     * 那些草稿挂在写作会话上。只查策划会话的话，它们永远不会出现在"待你确认"里。
     */
    @GetMapping("/projects/{projectId}/drafts")
    public ApiResponse<List<AgentDraft>> projectDrafts(@PathVariable Long projectId,
                                                       @RequestParam(defaultValue = "true") boolean pendingOnly) {
        return ApiResponse.ok(pendingOnly
                ? draftService.listPendingByProject(projectId)
                : draftService.listByProject(projectId));
    }

    /**
     * 某会话的草稿。
     *
     * <p>{@code pendingOnly=true} 就是界面上的"待确认"列表。
     */
    @GetMapping("/conversations/{conversationId}/drafts")
    public ApiResponse<List<AgentDraft>> drafts(@PathVariable Long conversationId,
                                                @RequestParam(defaultValue = "false") boolean pendingOnly) {
        return ApiResponse.ok(pendingOnly
                ? draftService.listPending(conversationId)
                : draftService.listByConversation(conversationId));
    }

    /**
     * 确认草稿 —— <b>到这一步才真正写入人物 / 设定 / 蓝图表</b>。
     *
     * <p>在这之前，Agent 的所有产出都只躺在 {@code agent_draft} 里，
     * 不可能影响用户的书。这是"人工确认"能真正生效的关键。
     */
    @PostMapping("/drafts/{draftId}/accept")
    public ApiResponse<AgentDraft> acceptDraft(@PathVariable Long draftId) {
        return ApiResponse.ok(draftService.accept(draftId));
    }

    /** 放弃草稿 */
    @PostMapping("/drafts/{draftId}/reject")
    public ApiResponse<AgentDraft> rejectDraft(@PathVariable Long draftId) {
        return ApiResponse.ok(draftService.reject(draftId));
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
