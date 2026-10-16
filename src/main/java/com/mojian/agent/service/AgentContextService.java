package com.mojian.agent.service;

import com.mojian.agent.context.AgentContextAssembler;
import com.mojian.agent.context.AgentContextAssembly;
import com.mojian.agent.context.AgentContextBudget;
import com.mojian.agent.context.AgentContextSection;
import com.mojian.agent.material.AgentMaterialLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文装配服务 —— 把三样东西串起来：<b>取数器、角色预算、组装器</b>。
 *
 * <pre>
 *   AgentMaterialLoader      取出该项目全部资料（人物/设定/摘要/正文）
 *          ↓
 *   AgentProfileService      按角色给出预算（上下文上限 + 输出预留）
 *          ↓
 *   AgentContextAssembler    装得下就全给，装不下按优先级裁
 *          ↓
 *   AgentContextAssembly     → 要注入提示词的文本 + 装填报表
 * </pre>
 *
 * <p>它同时是界面上"上下文用量 xx%"的数据源：那条进度条就是 {@code report().usageRatio()}。
 * 这个数字不是装饰 —— 到 80% 左右就该启用检索了，而这件事应该由数据提醒，不该靠人凭感觉判断。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentContextService {

    private final AgentMaterialLoader materialLoader;
    private final AgentProfileService profileService;

    /** 组装器是无状态纯逻辑，共享一个实例即可 */
    private final AgentContextAssembler assembler = new AgentContextAssembler();

    /**
     * 按角色预算装填整个项目的资料。
     *
     * @param agentRole     PLANNER / WRITER，决定用多大的预算
     * @param projectId     项目
     * @param extraSections 本次任务专属的材料（本章蓝图、章节写作要求等），
     *                      通常标为 REQUIRED —— 它们是"这次要干什么"，不能被裁掉
     */
    public AgentContextAssembly assemble(String agentRole, Long projectId,
                                         List<AgentContextSection> extraSections) {
        return assemble(agentRole, projectId, extraSections, null);
    }

    /**
     * 按角色预算装填，并可排除某一章。
     *
     * @param excludeChapterId 改稿时排除当前章：它已作为"本章现有稿"单独注入，
     *                         不该再以"前文原文"的名义出现一遍
     */
    public AgentContextAssembly assemble(String agentRole, Long projectId,
                                         List<AgentContextSection> extraSections,
                                         Long excludeChapterId) {
        AgentContextBudget budget = budgetOf(agentRole);

        List<AgentContextSection> sections = new ArrayList<>(
                materialLoader.loadAll(projectId, excludeChapterId));
        if (extraSections != null && !extraSections.isEmpty()) {
            sections.addAll(extraSections);
        }

        AgentContextAssembly assembly = assembler.assemble(sections, budget);
        log.info("[{}] 上下文装填：{}", agentRole, assembly.report().summary());
        return assembly;
    }

    public AgentContextAssembly assemble(String agentRole, Long projectId) {
        return assemble(agentRole, projectId, List.of(), null);
    }

    /** 该角色这次可用的预算（不解析密钥，只读配置 —— 用于展示与预估） */
    public AgentContextBudget budgetOf(String agentRole) {
        return profileService.budgetOf(agentRole);
    }
}
