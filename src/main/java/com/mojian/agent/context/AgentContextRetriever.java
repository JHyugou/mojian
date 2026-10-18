package com.mojian.agent.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 超预算时的章节检索兜底（轻量 BM25 风格）。
 *
 * <h3>为什么现在不做向量 RAG</h3>
 * 墨笺当前一本 13 章的书，全量材料约 10.4 万 token，而默认模型窗口 100 万（用量 12.5%），
 * <b>装得下就全给</b>永远优于检索 —— 检索必然漏东西。引入 embedding 还会绑定服务商能力：
 * OpenAI 兼容网关不一定提供 /embeddings，Key 与模型配置也只是一套。
 *
 * <p>但小窗口模型（128K）或超长篇（几十上百章）下材料会装不下，此时现有策略是
 * "严格优先级直接砍掉较老的章节"—— 会砍掉那些<b>当前情节真正相关</b>的旧章，
 * 比如这一章要回收第三章埋的伏笔，而第三章因为太老被整段丢弃。
 * 这里在"已经装不下"时才启用：保留最近 N 章，再按当前任务（指令 + 本章蓝图 + 标题）
 * 的<b>词面相关性</b>挑选旧章，而不是盲砍。
 *
 * <h3>为什么用词面匹配而不是向量</h3>
 * 中文小说里"专名、道具、地点、伏笔"大多以具体词出现，词面匹配对这类检索已足够；
 * 不需要外部 embedding 服务、结果可解释、可单测。真到语义检索成为瓶颈时，
 * 这个类就是替换点，而不是推翻上下文层。
 */
public final class AgentContextRetriever {

    /** 写作时永远保留的最近章节数：当前章的紧邻前文，连续性优先级最高 */
    private static final int RECENT_CHAPTERS = 3;

    /**
     * 查询词上限。
     *
     * <p>本章蓝图可能有几千字，二元组能切出上千个词；每个词都要在每章里 indexOf，
     * 超长篇时会把"检索兜底"变成一次卡顿。词面检索不需要那么全，
     * 前 240 个已经覆盖标题、专名和主要动作。
     */
    private static final int MAX_QUERY_TERMS = 240;

    private AgentContextRetriever() {
    }

    /**
     * 对已经取到的全量材料做一次"装不下时的检索计划"。
     *
     * @param sections 全量材料（含 REQUIRED；loader 已按优先级排过）
     * @param budget   本轮可用预算
     * @param query    当前任务文本（用户指令 / 本章蓝图 / 章节标题）
     * @return 计划结果：要么原样返回（装得下），要么保留必装+最近章+相关旧章
     */
    public static Result plan(List<AgentContextSection> sections, AgentContextBudget budget, String query) {
        if (sections == null || sections.isEmpty()) {
            return new Result(List.of(), List.of(), false);
        }
        int total = sections.stream().mapToInt(AgentContextSection::estimateTokens).sum();
        int usable = budget.usableTokens();
        if (total <= usable || query == null || query.isBlank()) {
            return new Result(sections, List.of(), false);
        }

        List<AgentContextSection> required = new ArrayList<>();
        List<AgentContextSection> fixedOptional = new ArrayList<>();
        List<AgentContextSection> chapters = new ArrayList<>();
        for (AgentContextSection section : sections) {
            if (section.required()) {
                required.add(section);
            } else if ("原文".equals(section.kind())) {
                chapters.add(section);
            } else {
                fixedOptional.add(section);
            }
        }
        if (chapters.isEmpty()) {
            return new Result(sections, List.of(), false);
        }

        int fixedCost = required.stream().mapToInt(AgentContextSection::estimateTokens).sum()
                + fixedOptional.stream().mapToInt(AgentContextSection::estimateTokens).sum();
        // 必装 + 最近章都放不下：这不是检索能救的，交给组装器按原规则报错/裁剪
        if (fixedCost >= usable) {
            return new Result(sections, List.of(), false);
        }

        int recentCount = Math.min(RECENT_CHAPTERS, chapters.size());
        List<AgentContextSection> recent = chapters.subList(0, recentCount);
        int recentCost = recent.stream().mapToInt(AgentContextSection::estimateTokens).sum();
        if (fixedCost + recentCost >= usable) {
            return new Result(sections, List.of(), false);
        }

        List<AgentContextSection> candidates = new ArrayList<>(chapters.subList(recentCount, chapters.size()));
        Set<String> terms = queryTerms(query);
        candidates.sort(Comparator
                .comparingInt((AgentContextSection section) -> score(section, terms)).reversed()
                .thenComparingInt(AgentContextSection::priority));

        int remaining = usable - fixedCost - recentCost;
        List<AgentContextSection> selected = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        for (AgentContextSection candidate : candidates) {
            int cost = candidate.estimateTokens();
            if (cost <= remaining) {
                selected.add(candidate);
                remaining -= cost;
            } else {
                dropped.add(candidate.title());
            }
        }

        if (selected.isEmpty()) {
            // 一章都塞不下：至少别把原有报表搞得更难排查，交回原清单
            return new Result(sections, List.of(), false);
        }

        List<AgentContextSection> planned = new ArrayList<>(required.size() + fixedOptional.size() + recent.size() + selected.size());
        planned.addAll(required);
        planned.addAll(fixedOptional);
        planned.addAll(recent);
        planned.addAll(selected);
        return new Result(List.copyOf(planned), List.copyOf(dropped), true);
    }

    /**
     * 查询词：中文取二元组（"龙骨"→"龙骨"；"云峰宗的家事"→"云峰","峰宗","宗的",...），
     * 英文/数字按单词取。二元组比单字更少噪声，又不需要中文分词器依赖。
     */
    static Set<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return terms;
        }
        String text = query.toLowerCase(Locale.ROOT);
        StringBuilder cjk = new StringBuilder();
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isCjk(ch)) {
                flushAscii(ascii, terms);
                cjk.append(ch);
            } else if (Character.isLetterOrDigit(ch)) {
                flushCjk(cjk, terms);
                ascii.append(ch);
            } else {
                flushCjk(cjk, terms);
                flushAscii(ascii, terms);
            }
        }
        flushCjk(cjk, terms);
        flushAscii(ascii, terms);
        return terms;
    }

    private static void flushCjk(StringBuilder buffer, Set<String> terms) {
        if (buffer.isEmpty()) {
            return;
        }
        String word = buffer.toString();
        if (word.length() == 1) {
            terms.add(word);
        } else {
            for (int i = 0; i + 1 < word.length() && terms.size() < MAX_QUERY_TERMS; i++) {
                terms.add(word.substring(i, i + 2));
            }
            // 完整的短专名（≤4 字）也直接作为词，权重更高
            if (word.length() <= 4 && terms.size() < MAX_QUERY_TERMS) {
                terms.add(word);
            }
        }
        buffer.setLength(0);
    }

    private static void flushAscii(StringBuilder buffer, Set<String> terms) {
        if (buffer.isEmpty()) {
            return;
        }
        if (terms.size() < MAX_QUERY_TERMS) {
            terms.add(buffer.toString());
        }
        buffer.setLength(0);
    }

    private static boolean isCjk(char ch) {
        return Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN;
    }

    /** 词面命中次数 × 词长；长词（专名/道具名）比常见双字词更有区分度 */
    private static int score(AgentContextSection section, Set<String> terms) {
        String text = (section.title() == null ? "" : section.title()) + "\n"
                + (section.content() == null ? "" : section.content());
        String haystack = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            int from = 0;
            int hits = 0;
            while (hits < 8) {
                int index = haystack.indexOf(term, from);
                if (index < 0) {
                    break;
                }
                hits++;
                from = index + term.length();
            }
            score += hits * Math.min(term.length(), 4);
        }
        return score;
    }

    /**
     * @param sections         实际要交给组装器的材料
     * @param droppedTitles    被检索策略主动放弃的旧章
     * @param retrievalApplied 是否真的启用了检索（装得下时为 false）
     */
    public record Result(List<AgentContextSection> sections, List<String> droppedTitles,
                         boolean retrievalApplied) {
    }
}
