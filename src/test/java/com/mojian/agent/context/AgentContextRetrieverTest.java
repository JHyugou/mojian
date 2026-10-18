package com.mojian.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超预算检索兜底的测试。
 *
 * <p>它守的是一条很容易退化回"盲砍"的行为：材料装不下时，不能只留最近几章，
 * 而是要把与当前任务相关的旧章捞回来（比如这章要回收第三章埋的伏笔）。
 */
class AgentContextRetrieverTest {

    private static AgentContextSection required(String title, int chars) {
        return AgentContextSection.required(title, "概".repeat(chars));
    }

    private static AgentContextSection chapter(String title, String content, int priority) {
        return AgentContextSection.optional("原文", title, content,
                priority, ContextSectionPolicy.TRUNCATE_TO_FIT);
    }

    private static List<AgentContextSection> chaptersNewestFirst() {
        List<AgentContextSection> sections = new ArrayList<>();
        sections.add(required("本书概况", 50));
        // loader 返回顺序：最近章在前
        for (int no = 8; no >= 1; no--) {
            String content = "无关的日常描写与过场。".repeat(10);
            if (no == 2) {
                content = "龙骨。这是第三章埋下的伏笔，龙骨在祭坛下面。".repeat(3);
            }
            sections.add(chapter("第 " + no + " 章", content, 40 + (8 - no)));
        }
        return sections;
    }

    @Test
    @DisplayName("装得下时完全不启用检索，材料原样返回")
    void 装得下不检索() {
        AgentContextBudget budget = AgentContextBudget.of(100_000, 8_192);
        AgentContextRetriever.Result result = AgentContextRetriever.plan(
                chaptersNewestFirst(), budget, "龙骨");

        assertThat(result.retrievalApplied()).isFalse();
        assertThat(result.droppedTitles()).isEmpty();
        assertThat(result.sections()).hasSize(9);
    }

    @Test
    @DisplayName("超预算：保留最近三章 + 按当前任务捞回相关旧章，而不是只砍最老的")
    void 超预算时检索相关旧章() {
        // usable = 600*0.9 = 540；必装 50 + 最近三章 300，只剩 190 给旧章（每章约 100）
        AgentContextBudget budget = AgentContextBudget.of(600, 0);

        AgentContextRetriever.Result result = AgentContextRetriever.plan(
                chaptersNewestFirst(), budget, "龙骨");

        assertThat(result.retrievalApplied()).isTrue();
        assertThat(result.sections()).extracting(AgentContextSection::title)
                .contains("本书概况", "第 8 章", "第 7 章", "第 6 章", "第 2 章")
                .doesNotContain("第 1 章", "第 3 章");
        assertThat(result.droppedTitles()).contains("第 1 章", "第 3 章");
    }

    @Test
    @DisplayName("查询词用中文二元组，命中专名而不是单字噪声")
    void 查询词切分() {
        assertThat(AgentContextRetriever.queryTerms("龙骨")).contains("龙骨");
        assertThat(AgentContextRetriever.queryTerms("云峰宗的家事")).contains("云峰", "峰宗", "家事");
        assertThat(AgentContextRetriever.queryTerms("rewrite 第3章")).contains("rewrite");
    }
}
