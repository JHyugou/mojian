package com.mojian.agent.service;

import com.mojian.agent.AgentContext;
import com.mojian.agent.AgentLoopService;
import com.mojian.agent.AgentPrompts;
import com.mojian.agent.AgentResult;
import com.mojian.agent.AgentRole;
import com.mojian.agent.AgentRunListener;
import com.mojian.agent.context.AgentContextAssembly;
import com.mojian.agent.context.AgentContextSection;
import com.mojian.agent.dto.PlannerChatResponse;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.entity.AgentPlanOption;
import com.mojian.agent.llm.AgentMessage;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 策划 Agent 的一次对话。
 *
 * <p>它把六件事按顺序串起来：
 * <ol>
 *   <li>取会话（没有就新建）</li>
 *   <li>读历史（在落本轮消息<b>之前</b>读，否则会把自己读进来）</li>
 *   <li>组装材料：全量注入 + 待选方案</li>
 *   <li>先把用户这轮的话落库（万一模型调用失败，用户的输入不会丢）</li>
 *   <li>跑循环</li>
 *   <li>把回答落库</li>
 * </ol>
 *
 * <p><b>为什么"待选方案"必须一起注入</b>：上一轮给了 3 条方案，用户这轮说"我选第二条"——
 * 而历史里只有"用户问 / Agent 答"，模型并不知道那 3 条分别是什么。
 * 不注入的话，多轮对话从第二轮起就会答非所问。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlannerService {

    /** 带进本轮请求的对话历史上限（字符）。材料本身已有明确预算，历史不该无限增长 */
    private static final int HISTORY_MAX_CHARS = 80_000;

    private final AgentLoopService agentLoopService;
    private final AgentContextService agentContextService;
    private final ConversationService conversationService;
    private final AgentProfileService profileService;
    private final AgentUsageService usageService;
    private final AgentRunRegistry runRegistry;
    private final AgentRunService runService;

    public PlannerChatResponse chat(Long projectId, Long conversationId, String userMessage,
                                       String runId) {
        // 1. 会话
        AgentConversation conversation = conversationId == null
                ? conversationService.create(projectId, AgentRole.PLANNER, userMessage)
                : conversationService.require(conversationId);
        requirePlannerConversation(conversation, projectId);

        // 2. 历史（必须在落本轮消息之前读）；按字符预算裁掉太早的轮次
        List<AgentMessage> history = ConversationService.trimHistory(
                conversationService.loadMessages(conversation.getId()), HISTORY_MAX_CHARS);

        // 3. 材料：全量注入 + 待选方案 + 已选定方案
        List<AgentContextSection> extra = new ArrayList<>();
        AgentContextSection pending = pendingOptionsSection(conversation.getId());
        if (pending != null) {
            extra.add(pending);
        }
        AgentContextSection selected = selectedOptionsSection(conversation.getId());
        if (selected != null) {
            extra.add(selected);
        }
        AgentContextAssembly materials = agentContextService.assemble(AgentRole.PLANNER, projectId, extra);
        String systemPrompt = AgentPrompts.planner(materials.text());

        // 4. 用户这轮的话先落库
        conversationService.append(conversation.getId(), 0, AgentMessage.user(userMessage));

        // 5. 跑循环
        AgentContext context = AgentContext.ofConversation(projectId, AgentRole.PLANNER, conversation.getId());

        /*
         * 收集思考过程。
         *
         * 策划走的是同步请求（一次返回完整结果），没有 SSE 可以把 reasoning 边生成边推，
         * 所以这里用 StringBuilder 攒起来、随响应一起返回。
         * 届不展示、怎么展示由界面设置决定 —— 后端只负责别把它丢了。
         * （在这之前这里没传 listener，思考过程是被直接丢弃的。）
         */
        StringBuilder reasoning = new StringBuilder();
        runRegistry.begin(runId);
        AgentRunListener listener = new AgentRunListener() {
            @Override
            public void onReasoning(String delta) {
                reasoning.append(delta);
            }

            /**
             * 用户点了停止。
             *
             * <p>前端断开连接是不够的 —— 服务端这边的线程会照跑不误，
             * 模型还在生成、钱还在花。所以真正的叫停靠这张登记表：
             * 循环每轮、每次动手执行工具之前都会问一次这里。
             */
            @Override
            public boolean cancelled() {
                return runRegistry.isCancelled(runId);
            }
        };
        AgentResult result;
        long startedAt = System.currentTimeMillis();
        try {
            result = agentLoopService.run(context, systemPrompt, history, userMessage,
                    maxSteps(), listener);
        } catch (RuntimeException e) {
            runService.recordFailure(context, System.currentTimeMillis() - startedAt, safeMessage(e));
            // 失败也要在会话里留一条回答：用户消息已经落库了，
            // 只留"用户说了话、没人应"的孤立消息，下次会被当历史带上，模型只会困惑。
            // （写作链路早就有这个兜底，策划这边之前漏了。）
            conversationService.append(conversation.getId(), 0, AgentMessage.assistant(
                    "这次没能完成：" + safeMessage(e) + "\n\n你的输入已经保留，可以直接再试一次。", null));
            throw e;
        } finally {
            runRegistry.end(runId);
        }

        // 6. 回答落库
        conversationService.append(conversation.getId(), result.steps(),
                AgentMessage.assistant(result.answer(), null));

        // 7. 记账 —— 花销要看得见，否则作者只能等到欠费才知道
        usageService.record(context, result.usage());
        runService.record(context, result, System.currentTimeMillis() - startedAt,
                materials.report().summary());

        log.info("策划对话完成（会话 {}）：{} 步 / {} / {}", conversation.getId(),
                result.steps(), result.status(), result.usage().describe());
        return PlannerChatResponse.of(conversation.getId(), result, materials.report(),
                reasoning.isEmpty() ? null : reasoning.toString());
    }

    /**
     * 把"还没被选定的方案"注入上下文 —— 用 REQUIRED（永不裁剪）：
     * 它很短，却是下一轮对话能不能接上的关键。
     */
    private AgentContextSection pendingOptionsSection(Long conversationId) {
        List<AgentPlanOption> pending = conversationService.listPlanOptions(conversationId).stream()
                .filter(option -> !Boolean.TRUE.equals(option.getSelected()))
                .toList();
        if (pending.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (AgentPlanOption option : pending) {
            sb.append("■ 方案 ").append(option.getOptionIndex()).append("：")
                    .append(option.getTitle()).append('\n');
            if (StringUtils.hasText(option.getSummary())) {
                sb.append(option.getSummary().trim()).append('\n');
            }
            if (StringUtils.hasText(option.getDetail())) {
                sb.append(option.getDetail().trim()).append('\n');
            }
            sb.append('\n');
        }
        sb.append("（用户还没有选定。如果用户说\"选第 2 条\"，指的就是上面的编号。）");
        return AgentContextSection.required("待用户选择的方案", sb.toString().trim());
    }

    /**
     * 用户已经选定的方案，也必须注入。
     *
     * <p>之前只注入"未选定"的方案，于是"选完了"的那一条反而从上下文里消失了 ——
     * 策划下一轮要把它落成蓝图时，根本没有细节可写。已选方案是<b>后续动作的依据</b>，
     * 和待选方案一样短、一样关键，所以同样标 REQUIRED。
     */
    private AgentContextSection selectedOptionsSection(Long conversationId) {
        List<AgentPlanOption> selected = conversationService.listPlanOptions(conversationId).stream()
                .filter(option -> Boolean.TRUE.equals(option.getSelected()))
                .toList();
        if (selected.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("用户已经从上一批方案里选定了下面这条。接下来的细化、落蓝图都以它为准；\n")
                .append("不要重新给出选项，也不要在方案之外另起炉灶。\n\n");
        for (AgentPlanOption option : selected) {
            sb.append("■ 方案 ").append(option.getOptionIndex()).append("：")
                    .append(option.getTitle()).append('\n');
            if (StringUtils.hasText(option.getSummary())) {
                sb.append(option.getSummary().trim()).append('\n');
            }
            if (StringUtils.hasText(option.getDetail())) {
                sb.append(option.getDetail().trim()).append('\n');
            }
            sb.append('\n');
        }
        return AgentContextSection.required("用户已选定的方案", sb.toString().trim());
    }

    private int maxSteps() {
        Integer configured = profileService.require(AgentRole.PLANNER).getMaxSteps();
        return configured == null ? 10 : configured;
    }

    /**
     * 会话归属校验。
     *
     * <p>前端曾经出现过"切项目/切模式后把策划消息发进写作会话"的串线，
     * 而服务端之前完全信任传上来的 conversationId。校验两层：
     * 角色必须是 PLANNER，项目必须与请求里的 projectId 一致。
     * 这是数据正确性的最后一道防线 —— 前端状态可以出 bug，服务端不该跟着错。
     */
    private void requirePlannerConversation(AgentConversation conversation, Long projectId) {
        if (!AgentRole.PLANNER.equals(conversation.getAgentRole())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "这段会话不是策划会话，不能在里面继续策划对话");
        }
        if (projectId != null && conversation.getProjectId() != null
                && !conversation.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "这段会话不属于当前项目，请回到它所属的项目继续");
        }
    }

    private String safeMessage(RuntimeException e) {
        if (e.getMessage() == null || e.getMessage().isBlank()) {
            return "服务暂时不可用";
        }
        return e.getMessage().length() > 300 ? e.getMessage().substring(0, 300) + "…" : e.getMessage();
    }
}
