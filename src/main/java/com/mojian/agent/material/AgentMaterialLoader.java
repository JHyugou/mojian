package com.mojian.agent.material;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mojian.agent.context.AgentContextSection;
import com.mojian.agent.context.ContextSectionPolicy;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.mapper.PlotOutlineMapper;
import com.mojian.project.entity.StoryProject;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 取数器 —— 决定 Agent"睁眼看到的世界"。
 *
 * <p>它做三件事：<b>从数据库取数 → 渲染成带标题的文本块 → 按优先级交给组装器</b>。
 * 组装器负责"装不装得下"，这里只负责"有什么、什么样子、谁更重要"。
 *
 * <h3>为什么是"全量注入"而不是检索</h3>
 * 墨笺现在全部可注入材料约 8.5 万 token，而模型窗口是 100 万 —— <b>装得下就全给</b>。
 * 检索是为了解决"装不下"，而筛选必然丢东西（挑漏了模型永远不知道有这东西）。
 * 所以这里的默认行为是：把整座图书馆推过去。
 *
 * <h3>为什么章节要一章一块，其余整类一块</h3>
 * 裁剪粒度要跟着"增长单元"走：
 * <ul>
 *   <li>人物 / 设定加起来才 5000 字，增长慢 → <b>整类打包成一块</b>，简单</li>
 *   <li>章节正文每章 +4300 字，是唯一会失控的一项 → <b>一章一块</b>，
 *       否则将来裁剪时只能"要么全留要么全删"，没法按章往前砍</li>
 * </ul>
 *
 * <h3>优先级顺序（数字小 = 重要 = 先装）</h3>
 * <pre>
 *   10  前情摘要      —— 连续性最重要，先知道前面发生过什么
 *   20  人物档案      —— 写谁要像谁
 *   30  世界观设定    —— 术语与规则一致
 *   40+ 章节正文      —— 按"离当前多远"排序：最近一章 40，往前依次 +1
 * </pre>
 * 注意"章节排在设定之后"是个<b>待校准的默认值</b>：预算真紧张时，
 * 最近一章原文可能比某条冷门设定更重要。阶段 6 用评测数据调，现在先给一个可解释的起点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentMaterialLoader {

    /** 前情摘要：连续性优先 */
    public static final int PRIORITY_MEMORY = 10;
    /** 已有蓝图：策划必须先知道"已经规划到哪了"，否则会重复规划或前后矛盾 */
    public static final int PRIORITY_OUTLINE = 15;
    /** 人物档案 */
    public static final int PRIORITY_CHARACTER = 20;
    /** 世界观设定 */
    public static final int PRIORITY_SETTING = 30;
    /** 章节正文基准优先级；实际值 = 基准 + 离当前的距离（最近一章 = 基准） */
    public static final int PRIORITY_CHAPTER_BASE = 40;

    /** 章节正文单块上限：真遇到超长章节不整段丢弃，先截断（组装器也认同这个策略） */
    private static final ContextSectionPolicy CHAPTER_POLICY = ContextSectionPolicy.TRUNCATE_TO_FIT;


    private final CharacterService characterService;
    private final SettingService settingService;
    private final ProjectService projectService;
    private final ChapterIndex chapterIndex;
    private final ChapterMemoryMapper chapterMemoryMapper;
    private final PlotOutlineMapper plotOutlineMapper;

    /**
     * 取该项目的<b>全部</b>可注入材料。
     *
     * <p>数量上不设限、也不做相关性筛选 —— 那是组装器在超预算时才该做的事。
     */
    public List<AgentContextSection> loadAll(Long projectId) {
        return loadAll(projectId, null);
    }

    /**
     * 取全部材料，但排除某一章。
     *
     * <p><b>为什么需要排除</b>：改稿时，"本章现有稿"会作为必备材料单独注入。
     * 如果这里再把同一章的正文当"前文原文"注入一遍，同一段文字会挂着两个标题
     * 出现在上下文里 —— 既浪费 token（一章五千字），也会让模型搞不清哪份才算数。
     */
    public List<AgentContextSection> loadAll(Long projectId, Long excludeChapterId) {
        List<AgentContextSection> sections = new ArrayList<>();
        // 概况放最前：书名、题材、文风、写作规则 —— 这是"这本书是什么"，
        // 比任何细节都更该先看到。漏了它，Agent 就会在完全不知道题材的情况下
        // 追问"你要写玄幻还是都市"，而作者明明已经在项目里填过了。
        addIfPresent(sections, projectSection(projectId));
        addIfPresent(sections, memorySection(projectId));
        addIfPresent(sections, outlineSection(projectId));
        addIfPresent(sections, characterSection(projectId));
        addIfPresent(sections, settingSection(projectId));
        sections.addAll(chapterSections(projectId, excludeChapterId));
        log.debug("取数完成：项目 {} 共 {} 块材料，合计约 {} token",
                projectId, sections.size(),
                sections.stream().mapToInt(AgentContextSection::estimateTokens).sum());
        return sections;
    }

    /**
     * 本书概况：书名、题材、整体文风、简介、项目级写作规则。
     *
     * <p>这一块定为 <b>REQUIRED</b>：它只有几百字，却决定了后面所有判断的底色。
     * 宁可裁掉几章原文，也不能让 Agent 不知道自己在写什么题材的书。
     */
    private AgentContextSection projectSection(Long projectId) {
        StoryProject project = projectService.requireById(projectId);
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "书名", project.getName());
        appendLine(sb, "题材", project.getGenre());
        appendLine(sb, "整体文风", project.getStyle());
        appendLine(sb, "简介", project.getIntro());
        // 写作规则是"必须遵守"的，措辞上把它和其余信息区分开 ——
        // 模型对"设定"和"要求"的处理方式不一样
        if (StringUtils.hasText(project.getWritingRules())) {
            sb.append("【本书写作规则（必须遵守）】\n").append(project.getWritingRules().trim()).append('\n');
        }
        if (sb.isEmpty()) {
            return null;
        }
        return AgentContextSection.required("本书概况", sb.toString().trim());
    }

    private void appendLine(StringBuilder sb, String label, String value) {
        if (StringUtils.hasText(value)) {
            sb.append(label).append('：').append(value.trim()).append('\n');
        }
    }

    /** 只要最近 N 章的正文（写作 Agent 的"近章原文"策略会用到） */
    public List<AgentContextSection> loadRecentChapterTexts(Long projectId, int count) {
        List<AgentContextSection> all = chapterSections(projectId, null);
        return all.size() <= count ? all : all.subList(0, count);
    }

    // ==================== 人物 ====================

    private AgentContextSection characterSection(Long projectId) {
        List<CharacterProfile> characters = characterService.list(projectId).stream()
                .filter(c -> !Boolean.FALSE.equals(c.getEnabled()))
                .toList();
        if (characters.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (CharacterProfile character : characters) {
            appendEntry(sb, character.getName(),
                    StringUtils.hasText(character.getRoleIdentity()) ? character.getRoleIdentity() : null,
                    character.getDescription());
        }
        return AgentContextSection.optional("人物", "人物档案", sb.toString().trim(),
                PRIORITY_CHARACTER, ContextSectionPolicy.TRUNCATE_TO_FIT);
    }

    // ==================== 设定 ====================

    private AgentContextSection settingSection(Long projectId) {
        List<SettingEntry> settings = settingService.list(projectId, null).stream()
                .filter(s -> !Boolean.FALSE.equals(s.getEnabled()))
                .toList();
        if (settings.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (SettingEntry setting : settings) {
            // 标签与触发词一并带上：它们既帮模型理解这条设定属于哪一块，
            // 也是将来"超预算时按关键词筛"的索引
            List<String> marks = new ArrayList<>();
            if (StringUtils.hasText(setting.getTags())) {
                marks.add("标签：" + setting.getTags().trim());
            }
            if (StringUtils.hasText(setting.getTriggerKeywords())) {
                marks.add("触发词：" + setting.getTriggerKeywords().trim());
            }
            appendEntry(sb, setting.getTitle(),
                    marks.isEmpty() ? null : String.join("｜", marks),
                    setting.getContent());
        }
        return AgentContextSection.optional("设定", "世界观设定", sb.toString().trim(),
                PRIORITY_SETTING, ContextSectionPolicy.TRUNCATE_TO_FIT);
    }

    // ==================== 前情摘要 ====================

    /**
     * 前情摘要合并成一块。
     *
     * <p>它是"连续性"的关键：写第 13 章时最需要的不是第 1 章原文，而是"第 1~12 章各发生了什么"。
     * 所以优先级给到最高一档，且不按章拆分（单条摘要本该很短）。
     */
    private AgentContextSection memorySection(Long projectId) {
        List<Chapter> chapters = chapterIndex.ordered(projectId);
        if (chapters.isEmpty()) {
            return null;
        }
        Map<Long, String> memories = memoriesByChapter(
                chapters.stream().map(Chapter::getId).toList());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chapters.size(); i++) {
            Chapter chapter = chapters.get(i);
            String memory = memories.get(chapter.getId());
            if (!StringUtils.hasText(memory)) {
                continue;
            }
            sb.append("■ 第 ").append(i + 1).append(" 章");
            if (StringUtils.hasText(chapter.getTitle())) {
                sb.append("｜").append(chapter.getTitle().trim());
            }
            sb.append('\n').append(memory.trim()).append("\n\n");
        }
        if (sb.isEmpty()) {
            return null;
        }
        return AgentContextSection.optional("摘要", "前情摘要", sb.toString().trim(),
                PRIORITY_MEMORY, ContextSectionPolicy.TRUNCATE_TO_FIT);
    }

    // ==================== 章节正文 ====================

    /**
     * 每章一块。返回顺序是<b>离当前最近的在前</b>（第 N 章、第 N-1 章……），
     * 且优先级随距离递增 —— 于是组装器的"严格优先级裁剪"天然会从最远的章节开始砍。
     *
     * @param excludeChapterId 排除某章（改稿时用：那一章会作为"本章现有稿"单独注入，
     *                         不能再以"前文原文"的名义出现一遍）
     */
    private List<AgentContextSection> chapterSections(Long projectId, Long excludeChapterId) {
        List<Chapter> chapters = chapterIndex.ordered(projectId);
        List<AgentContextSection> sections = new ArrayList<>();

        // orderedChapters 是正序（第 1 章在前），从后往前生成，最近的一章先入列。
        // distance 按"在结果里的位置"算，而不是按原始序号 —— 否则排除一章之后优先级会出现空洞
        int distance = 0;
        for (int i = chapters.size() - 1; i >= 0; i--) {
            Chapter chapter = chapters.get(i);
            if (excludeChapterId != null && excludeChapterId.equals(chapter.getId())) {
                continue;
            }
            String label = "第 " + (i + 1) + " 章"
                    + (StringUtils.hasText(chapter.getTitle()) ? "｜" + chapter.getTitle().trim() : "");
            String content = StringUtils.hasText(chapter.getContent())
                    ? chapter.getContent().trim()
                    : "（本章尚未开写）";
            sections.add(AgentContextSection.optional("原文", label, content,
                    PRIORITY_CHAPTER_BASE + distance, CHAPTER_POLICY));
            distance++;
        }
        return sections;
    }

    // ==================== 取数细节 ====================

    // ==================== 已有蓝图 ====================

    /**
     * 已有蓝图树（缩进渲染）。
     *
     * <p>策划 Agent 必须先知道"已经规划到哪了"：否则它会重复规划已经定好的部分，
     * 或者新加的节点和已有结构前后矛盾。这是最容易漏、后果也最直接的一类材料。
     */
    private AgentContextSection outlineSection(Long projectId) {
        List<PlotOutline> nodes = plotOutlineMapper.selectList(
                new LambdaQueryWrapper<PlotOutline>().eq(PlotOutline::getProjectId, projectId));
        if (nodes.isEmpty()) {
            return null;
        }

        Map<Long, List<PlotOutline>> children = new HashMap<>();
        List<PlotOutline> roots = new ArrayList<>();
        for (PlotOutline node : nodes) {
            if (node.getParentId() == null) {
                roots.add(node);
            } else {
                children.computeIfAbsent(node.getParentId(), k -> new ArrayList<>()).add(node);
            }
        }
        sortByOrder(roots);
        children.values().forEach(this::sortByOrder);

        StringBuilder sb = new StringBuilder();
        Set<Long> visited = new HashSet<>();
        for (PlotOutline root : roots) {
            appendOutlineNode(sb, root, 0, children, visited);
        }
        // 父节点缺失的孤儿节点也要出现，不能让它们凭空消失
        for (PlotOutline node : nodes) {
            if (!visited.contains(node.getId())) {
                appendOutlineNode(sb, node, 0, children, visited);
            }
        }
        return AgentContextSection.optional("蓝图", "已有蓝图", sb.toString().trim(),
                // 超预算时宁可"截短"也不要"整段消失"：蓝图没了，策划会重复规划、写作会跑偏
                PRIORITY_OUTLINE, ContextSectionPolicy.TRUNCATE_TO_FIT);
    }

    private void appendOutlineNode(StringBuilder sb, PlotOutline node, int depth,
                                   Map<Long, List<PlotOutline>> children, Set<Long> visited) {
        if (!visited.add(node.getId())) {
            return; // 数据成环时保护：宁可少渲染，也不要死循环
        }
        sb.append("  ".repeat(depth)).append("■ ").append(node.getName() == null ? "未命名" : node.getName());
        if (StringUtils.hasText(node.getNodeType())) {
            sb.append("（").append(node.getNodeType()).append("）");
        }
        sb.append('\n');
        if (StringUtils.hasText(node.getSummary())) {
            sb.append("  ".repeat(depth + 1)).append(summarize(node.getSummary())).append('\n');
        }
        for (PlotOutline child : children.getOrDefault(node.getId(), List.of())) {
            appendOutlineNode(sb, child, depth + 1, children, visited);
        }
    }

    /**
     * 蓝图摘要原样返回，<b>不按节点截断</b>。
     *
     * <p>这里原来有个 200 字的硬上限，理由是"100 章 × 6000 字会撑爆上下文"。
     * 实测后发现这个顾虑在当前模型上不成立，而代价却很实在：
     * 某本书 14 个节点共 11,172 字，截断后只剩 2,341 字 ——
     * <b>少注入了 8,831 字，占可用上下文的 0.55%</b>。
     *
     * <p>更糟的是被砍得最狠的往往正是"当前要写的那一章"的完整方案
     * （实测有 4,152 字被砍到 200）—— 而写作 Agent 最需要的就是它。
     *
     * <p>所以改成：<b>全量注入，超出预算时交给组装器</b>
     * （上面的策略已改为 TRUNCATE_TO_FIT，会先截短而不是整段丢弃）。
     * 组装器本来就是干这个的 —— 在材料层再压一道，等于让同一个职责
     * 出现在两个地方，而两边都不知道对方压了多少。
     */
    private String summarize(String summary) {
        return summary.trim();
    }

    private void sortByOrder(List<PlotOutline> nodes) {
        nodes.sort(Comparator
                .comparing((PlotOutline n) -> n.getSortOrder() == null ? 0 : n.getSortOrder())
                .thenComparing(n -> n.getId() == null ? 0L : n.getId()));
    }

    /** 一次查全部记忆，避免 N 次查库（章节多起来时差别很明显） */
    private Map<Long, String> memoriesByChapter(List<Long> chapterIds) {
        if (chapterIds.isEmpty()) {
            return Map.of();
        }
        List<ChapterMemory> memories = chapterMemoryMapper.selectList(
                new LambdaQueryWrapper<ChapterMemory>().in(ChapterMemory::getChapterId, chapterIds));
        Map<Long, String> map = new HashMap<>();
        for (ChapterMemory memory : memories) {
            map.put(memory.getChapterId(), memory.getMemory());
        }
        return map;
    }

    // ==================== 渲染 ====================

    /**
     * 统一渲染成"■ 标题｜补充 + 正文"。
     *
     * <p>为什么带标题：模型一眼知道哪儿有什么，也能在回答里引用
     * （"根据【人物档案】里的寒舟……"）；定位错了人也能立刻看出来。
     */
    private void appendEntry(StringBuilder sb, String title, String supplement, String body) {
        sb.append("■ ").append(title == null ? "未命名" : title.trim());
        if (StringUtils.hasText(supplement)) {
            sb.append("｜").append(supplement.trim());
        }
        sb.append('\n');
        if (StringUtils.hasText(body)) {
            sb.append(body.trim()).append('\n');
        }
        sb.append('\n');
    }

    private void addIfPresent(List<AgentContextSection> sections, AgentContextSection section) {
        if (section != null) {
            sections.add(section);
        }
    }
}
