package com.mojian.agent.service;

import com.mojian.agent.AgentContext;
import com.mojian.agent.entity.AgentUsageLog;
import com.mojian.agent.llm.TokenUsage;
import com.mojian.agent.mapper.AgentUsageLogMapper;
import com.mojian.agent.usage.ModelPricing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 用量记账。
 *
 * <p>为什么值得单独做一件事：<b>Agent 的花销是不可见的</b> ——
 * 它不像点外卖那样有个明确的"提交"动作，而是在对话里一次次调用悄悄累积。
 * 没有流水，作者能感知到的只有两种状态："好像还行"和"怎么欠费了"。
 *
 * <p>（这个功能是被真实事故逼出来的：之前因为连续实测，把某家服务商的额度跑到了欠费，
 * 而当时唯一的依据是事后去后台翻账单。）
 *
 * <p>记账本身绝不能影响主流程：写失败只记一条 WARN 就算了 ——
 * 因为记不上账而让用户的一次对话失败，那是本末倒置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentUsageService {

    private final AgentUsageLogMapper usageLogMapper;
    private final AgentProfileService profileService;

    /**
     * 记一次运行的花销。
     *
     * @param context 本次运行的上下文（项目、会话、角色）
     * @param usage   累计用量；为空或全零时跳过（不值得为一次没花钱的运行写一行）
     */
    public void record(AgentContext context, TokenUsage usage) {
        if (usage == null || usage.totalTokens() <= 0) {
            return;
        }
        try {
            String model = profileService.resolve(context.agentRole()).model();
            AgentUsageLog row = new AgentUsageLog();
            row.setProjectId(context.projectId());
            row.setConversationId(context.conversationId());
            row.setAgentRole(context.agentRole());
            row.setModel(model);
            row.setPromptTokens(usage.promptTokens());
            row.setCachedTokens(usage.cachedTokens());
            row.setCompletionTokens(usage.completionTokens());
            BigDecimal cost = ModelPricing.estimateCost(model, usage.promptTokens(),
                    usage.cachedTokens(), usage.completionTokens());
            row.setEstimatedCost(cost);
            usageLogMapper.insert(row);

            // 注意币种：定价表是美元，别在界面上标成 ¥（真实数据里为此困惑过一次）
            log.info("[{}] 本次运行花费估算 ${}（{}）", context.agentRole(), cost, usage.describe());
        } catch (Exception e) {
            // 记账失败不该让用户的这次对话跟着失败
            log.warn("记录用量失败（不影响本次运行）: {}", e.getMessage());
        }
    }

    // ==================== 汇报 ====================

    /** 最近的调用流水 */
    public List<AgentUsageLog> recent(int limit) {
        return usageLogMapper.selectRecent(Math.min(Math.max(limit, 1), 200));
    }

    /** 最近 N 天的汇总 —— 界面上"这段时间花了多少"靠它 */
    public UsageSummary summary(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(days, 1));
        return new UsageSummary(days, usageLogMapper.countSince(since),
                usageLogMapper.sumCostSince(since));
    }

    public record UsageSummary(int days, long calls, BigDecimal cost) {
    }
}
