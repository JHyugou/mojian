package com.mojian.agent.service;

import com.mojian.agent.AgentContext;
import com.mojian.agent.AgentLoopService;
import com.mojian.agent.AgentPrompts;
import com.mojian.agent.AgentResult;
import com.mojian.agent.AgentRole;
import com.mojian.agent.context.AgentContextAssembly;
import com.mojian.agent.context.AgentContextSection;
import com.mojian.agent.dto.PlannerChatResponse;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.entity.AgentPlanOption;
import com.mojian.agent.llm.AgentMessage;
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

    private final AgentLoopService agentLoopService;
    private final AgentContextService agentContextService;
    private final ConversationService conversationService;
    private final AgentProfileService profileService;
    private final AgentUsageService usageService;

    public PlannerChatResponse chat(Long projectId, Long conversationId, String userMessage) {
        // 1. 会话
        AgentConversation conversation = conversationId == null
                ? conversationService.create(projectId, AgentRole.PLANNER, userMessage)
                : conversationService.require(conversationId);

        // 2. 历史（必须在落本轮消息之前读）
        List<AgentMessage> history = conversationService.loadMessages(conversation.getId());

        // 3. 材料：全量注入 + 上一轮尚未选定的方案
        List<AgentContextSection> extra = new ArrayList<>();
        AgentContextSection pending = pendingOptionsSection(conversation.getId());
        if (pending != null) {
            extra.add(pending);
        }
        AgentContextAssembly materials = agentContextService.assemble(AgentRole.PLANNER, projectId, extra);
        String systemPrompt = AgentPrompts.planner(materials.text());

        // 4. 用户这轮的话先落库
        conversationService.append(conversation.getId(), 0, AgentMessage.user(userMessage));

        // 5. 跑循环
        AgentContext context = AgentContext.ofConversation(projectId, AgentRole.PLANNER, conversation.getId());
        AgentResult result = agentLoopService.run(context, systemPrompt, history, userMessage, maxSteps());

        // 6. 回答落库
        conversationService.append(conversation.getId(), result.steps(),
                AgentMessage.assistant(result.answer(), null));

        // 7. 记账 —— 花销要看得见，否则作者只能等到欠费才知道
        usageService.record(context, result.usage());

        log.info("策划对话完成（会话 {}）：{} 步 / {} / {}", conversation.getId(),
                result.steps(), result.status(), result.usage().describe());
        return PlannerChatResponse.of(conversation.getId(), result, materials.report());
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

    private int maxSteps() {
        Integer configured = profileService.require(AgentRole.PLANNER).getMaxSteps();
        return configured == null ? 10 : configured;
    }
}
