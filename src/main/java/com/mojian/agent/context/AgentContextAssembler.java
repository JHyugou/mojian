package com.mojian.agent.context;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 上下文组装器 —— 决定"这一次发给模型的材料里到底装了什么"。
 *
 * <p>本类<b>只做装填决策，不碰数据库</b>：取数是调用方的事。这样它就是个纯函数，
 * 能被彻底测透（这是整个改造里最容易出错、也最值得测的一块）。
 *
 * <p><b>装填规则</b>：
 * <ol>
 *   <li>必需材料先装，永不裁剪；连必需材料都装不下就直接报错（配置问题，不该悄悄降级）</li>
 *   <li>其余材料按 priority 升序装填（数字小 = 重要 = 先装）</li>
 *   <li><b>严格优先级</b>：一旦有一段装不下就停止，后面的不再尝试。
 *       这是刻意的 —— 对"原文"类材料，绝不允许出现"装了第 3 章却没装第 12 章"的断裂；
 *       优先级顺序已经表达了重要性，后面的本来就更不重要</li>
 *   <li>装不下的材料按各自策略处理：整段丢弃，或截断到剩余空间</li>
 * </ol>
 *
 * <p><b>典型优先级分配</b>（由调用方决定）：前情摘要 10 → 相关人物 20 → 相关设定 30
 * → 最近章原文 40 → 更早章原文 45、46……（越远数字越大）
 *
 * <p>注意：{@code tools} 字段（工具清单）与系统提示词也占 token，但本类看不到它们。
 * 所以调用方配置 {@link AgentContextBudget} 时要把这些固定开销一并算进预留里。
 */
@Slf4j
public class AgentContextAssembler {

    /** 截断后至少要有这么多 token 才值得截；否则不如整段不要，免得模型只看到一堆碎片 */
    private static final int MIN_TRUNCATE_TOKENS = 200;

    public AgentContextAssembly assemble(List<AgentContextSection> sections, AgentContextBudget budget) {
        int usable = budget.usableTokens();

        List<AgentContextSection> required = new ArrayList<>();
        List<AgentContextSection> optional = new ArrayList<>();
        for (AgentContextSection section : sections) {
            (section.required() ? required : optional).add(section);
        }

        int used = required.stream().mapToInt(AgentContextSection::estimateTokens).sum();
        if (used > usable) {
            throw new IllegalStateException(String.format(
                    "必需材料 %d token 已超过可用预算 %d token（上下文上限 %d，预留 %d）。"
                            + "请缩短系统提示词/本章蓝图，或换用上下文更大的模型。",
                    used, usable, budget.contextLimitTokens(), budget.reserveOutputTokens()));
        }

        List<AgentContextSection> kept = new ArrayList<>(required);
        List<String> trimmed = new ArrayList<>();

        List<AgentContextSection> ordered = optional.stream()
                .sorted(Comparator.comparingInt(AgentContextSection::priority))
                .toList();

        for (AgentContextSection section : ordered) {
            int cost = section.estimateTokens();
            if (used + cost <= usable) {
                kept.add(section);
                used += cost;
                continue;
            }

            if (section.policy() == ContextSectionPolicy.TRUNCATE_TO_FIT) {
                int room = usable - used;
                if (room >= MIN_TRUNCATE_TOKENS) {
                    AgentContextSection cut = section.truncateTo(room);
                    kept.add(cut);
                    used += cut.estimateTokens();
                    trimmed.add(section.title() + "（已截断）");
                } else {
                    trimmed.add(section.title());
                }
            } else {
                trimmed.add(section.title());
            }
            break;
        }

        String text = render(kept);
        AgentContextReport report = new AgentContextReport(usable, used,
                kept.stream().map(AgentContextSection::title).toList(),
                List.copyOf(trimmed));
        log.debug("上下文组装完成：{}", report.summary());
        return new AgentContextAssembly(text, report);
    }

    /** 渲染成给模型看的材料块，每段带【标题】便于模型引用 */
    private String render(List<AgentContextSection> sections) {
        StringBuilder sb = new StringBuilder();
        for (AgentContextSection section : sections) {
            if (section.content() == null || section.content().isBlank()) {
                continue;
            }
            sb.append("【").append(section.title()).append("】\n")
                    .append(section.content().strip()).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }
}
