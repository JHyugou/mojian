package com.mojian.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上下文组装器的行为测试。
 *
 * <p>这里断言的是"装填规则"，不是"某个例子的输出"。规则一旦被破坏，
 * 线上表现就是"模型莫名其妙忘了前文"或者"突然报上下文超限"，都很难排查，
 * 所以在这里锁死。
 *
 * <p>测试里的 token 数即字符数（1 汉字 ≈ 1 token，实测结论）。
 */
class AgentContextAssemblerTest {

    private final AgentContextAssembler assembler = new AgentContextAssembler();

    /** 预算 1000 token，不预留输出 → 实际可用 900（安全系数 0.9） */
    private static final AgentContextBudget BUDGET = AgentContextBudget.of(1000, 0);

    private static String text(int length) {
        return "字".repeat(length);
    }

    private static AgentContextSection dropIfNotFit(String title, int length, int priority) {
        return AgentContextSection.optional("原文", title, text(length), priority,
                ContextSectionPolicy.DROP_IF_NOT_FIT);
    }

    // ==================== 全量优先 ====================

    @Test
    @DisplayName("预算充足：全部装入，不做任何裁剪")
    void 预算充足时全量装入() {
        List<AgentContextSection> sections = List.of(
                AgentContextSection.required("本章蓝图", text(100)),
                dropIfNotFit("前情摘要", 200, 10),
                dropIfNotFit("第 12 章", 300, 40),
                dropIfNotFit("第 11 章", 250, 41));

        AgentContextAssembly assembly = assembler.assemble(sections, BUDGET);

        assertThat(assembly.report().trimmedTitles()).isEmpty();
        assertThat(assembly.report().keptTitles())
                .containsExactly("本章蓝图", "前情摘要", "第 12 章", "第 11 章");
        assertThat(assembly.report().usedTokens()).isEqualTo(850);
        assertThat(assembly.text()).contains("【本章蓝图】").contains("【第 12 章】");
    }

    // ==================== 预算不足时的裁剪 ====================

    @Test
    @DisplayName("预算不足：按优先级装填，装不下的被裁掉")
    void 预算不足时按优先级裁剪() {
        List<AgentContextSection> sections = List.of(
                AgentContextSection.required("本章蓝图", text(100)),
                dropIfNotFit("前情摘要", 300, 10),
                dropIfNotFit("第 12 章", 300, 40),
                dropIfNotFit("第 11 章", 300, 41));

        AgentContextAssembly assembly = assembler.assemble(sections, BUDGET);

        // 100 + 300 + 300 = 700；再加 300 就变 1000 > 900
        assertThat(assembly.report().keptTitles()).containsExactly("本章蓝图", "前情摘要", "第 12 章");
        assertThat(assembly.report().trimmedTitles()).containsExactly("第 11 章");
        assertThat(assembly.report().usedTokens()).isEqualTo(700);
    }

    @Test
    @DisplayName("严格优先级：一旦有材料装不下，后面的不再尝试（防止原文出现断裂）")
    void 装不下之后不再尝试后续材料() {
        List<AgentContextSection> sections = List.of(
                AgentContextSection.required("本章蓝图", text(100)),
                dropIfNotFit("第 12 章", 900, 40),
                dropIfNotFit("一条小设定", 50, 99));

        AgentContextAssembly assembly = assembler.assemble(sections, BUDGET);

        assertThat(assembly.report().keptTitles()).containsExactly("本章蓝图");
        // 严格优先级：后面的也不再尝试；但报表要把它们全部如实记成"被裁掉"，
        // 否则"裁掉 1 段"可能是 50 章原文全没了，排查时会被报表误导。
        assertThat(assembly.report().trimmedTitles()).containsExactly("第 12 章", "一条小设定");
        assertThat(assembly.text()).doesNotContain("一条小设定");
    }

    // ==================== 必需材料 ====================

    @Test
    @DisplayName("必需材料永不裁剪：预算再紧也保留")
    void 必需材料永不裁剪() {
        List<AgentContextSection> sections = List.of(
                AgentContextSection.required("本章蓝图", text(800)),
                dropIfNotFit("前情摘要", 300, 10));

        AgentContextAssembly assembly = assembler.assemble(sections, BUDGET);

        assertThat(assembly.report().keptTitles()).containsExactly("本章蓝图");
        assertThat(assembly.report().usedTokens()).isEqualTo(800);
    }

    @Test
    @DisplayName("必需材料就超预算：直接报错并说明原因，不悄悄降级")
    void 必需材料超预算直接报错() {
        List<AgentContextSection> sections = List.of(
                AgentContextSection.required("本章蓝图", text(1000)));

        assertThatThrownBy(() -> assembler.assemble(sections, BUDGET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必需材料")
                .hasMessageContaining("900");
    }

    // ==================== 截断策略 ====================

    @Test
    @DisplayName("可截断材料装不下：截断到剩余空间并留下提示，把预算用满")
    void 可截断材料截断到剩余空间() {
        List<AgentContextSection> sections = List.of(
                AgentContextSection.required("本章蓝图", text(100)),
                AgentContextSection.optional("原文", "第 12 章", text(1000), 40,
                        ContextSectionPolicy.TRUNCATE_TO_FIT));

        AgentContextAssembly assembly = assembler.assemble(sections, BUDGET);

        assertThat(assembly.report().usedTokens()).isEqualTo(900);
        assertThat(assembly.report().trimmedTitles()).containsExactly("第 12 章（已截断）");
        assertThat(assembly.text()).contains(AgentTokenEstimator.TRUNCATE_MARKER);
    }

    @Test
    @DisplayName("剩余空间不足 200 token 时不截断：整段丢弃，免得模型只看到碎片")
    void 剩余空间太小时不截断() {
        List<AgentContextSection> sections = List.of(
                AgentContextSection.required("本章蓝图", text(880)),
                AgentContextSection.optional("原文", "第 12 章", text(1000), 40,
                        ContextSectionPolicy.TRUNCATE_TO_FIT));

        AgentContextAssembly assembly = assembler.assemble(sections, BUDGET);

        assertThat(assembly.report().trimmedTitles()).containsExactly("第 12 章");
        assertThat(assembly.report().usedTokens()).isEqualTo(880);
        assertThat(assembly.text()).doesNotContain(AgentTokenEstimator.TRUNCATE_MARKER);
    }

    // ==================== 渲染与报表 ====================

    @Test
    @DisplayName("空材料不渲染出空标题")
    void 空材料不渲染() {
        List<AgentContextSection> sections = List.of(
                AgentContextSection.optional("人物", "寒舟", "   ", 20, ContextSectionPolicy.DROP_IF_NOT_FIT),
                AgentContextSection.optional("人物", "陌", text(50), 21, ContextSectionPolicy.DROP_IF_NOT_FIT));

        AgentContextAssembly assembly = assembler.assemble(sections, BUDGET);

        assertThat(assembly.text()).doesNotContain("【寒舟】").contains("【陌】");
    }

    @Test
    @DisplayName("报表摘要能直接给人看：用多少、装了几段、裁了什么")
    void 报表摘要可读() {
        AgentContextAssembly assembly = assembler.assemble(List.of(
                AgentContextSection.required("本章蓝图", text(100)),
                dropIfNotFit("第 12 章", 900, 40)), BUDGET);

        assertThat(assembly.report().summary())
                .contains("100 / 900")
                .contains("裁掉 1 段")
                .contains("第 12 章");
        assertThat(assembly.report().hasTrimmed()).isTrue();
        assertThat(assembly.report().usageRatio()).isBetween(0.10, 0.12);
    }

    // ==================== 预算参数校验 ====================

    @Test
    @DisplayName("预算参数非法时直接报错（防止把 0 当成'随便装'）")
    void 预算参数校验() {
        assertThatThrownBy(() -> AgentContextBudget.of(0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentContextBudget.of(1000, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(AgentContextBudget.conservative().usableTokens()).isPositive();
    }
}
