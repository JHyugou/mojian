package com.mojian.agent.material;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mojian.agent.context.AgentContextSection;
import com.mojian.agent.context.ContextSectionPolicy;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.mapper.ChapterMapper;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
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
import java.util.List;
import java.util.Map;

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
    private final ChapterMapper chapterMapper;
    private final ChapterMemoryMapper chapterMemoryMapper;

    /**
     * 取该项目的<b>全部</b>可注入材料。
     *
     * <p>数量上不设限、也不做相关性筛选 —— 那是组装器在超预算时才该做的事。
     */
    public List<AgentContextSection> loadAll(Long projectId) {
        List<AgentContextSection> sections = new ArrayList<>();
        addIfPresent(sections, characterSection(projectId));
        addIfPresent(sections, settingSection(projectId));
        addIfPresent(sections, memorySection(projectId));
        sections.addAll(chapterSections(projectId));
        log.debug("取数完成：项目 {} 共 {} 块材料，合计约 {} token",
                projectId, sections.size(),
                sections.stream().mapToInt(AgentContextSection::estimateTokens).sum());
        return sections;
    }

    /** 只要最近 N 章的正文（写作 Agent 的"近章原文"策略会用到） */
    public List<AgentContextSection> loadRecentChapterTexts(Long projectId, int count) {
        List<AgentContextSection> all = chapterSections(projectId);
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
                PRIORITY_CHARACTER, ContextSectionPolicy.DROP_IF_NOT_FIT);
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
                PRIORITY_SETTING, ContextSectionPolicy.DROP_IF_NOT_FIT);
    }

    // ==================== 前情摘要 ====================

    /**
     * 前情摘要合并成一块。
     *
     * <p>它是"连续性"的关键：写第 13 章时最需要的不是第 1 章原文，而是"第 1~12 章各发生了什么"。
     * 所以优先级给到最高一档，且不按章拆分（单条摘要本该很短）。
     */
    private AgentContextSection memorySection(Long projectId) {
        List<Chapter> chapters = orderedChapters(projectId);
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
                PRIORITY_MEMORY, ContextSectionPolicy.DROP_IF_NOT_FIT);
    }

    // ==================== 章节正文 ====================

    /**
     * 每章一块。返回顺序是<b>离当前最近的在前</b>（第 N 章、第 N-1 章……），
     * 且优先级随距离递增 —— 于是组装器的"严格优先级裁剪"天然会从最远的章节开始砍。
     */
    private List<AgentContextSection> chapterSections(Long projectId) {
        List<Chapter> chapters = orderedChapters(projectId);
        List<AgentContextSection> sections = new ArrayList<>();

        // orderedChapters 是正序（第 1 章在前），从后往前生成，最近的一章先入列
        for (int i = chapters.size() - 1; i >= 0; i--) {
            Chapter chapter = chapters.get(i);
            int distance = chapters.size() - 1 - i;
            String label = "第 " + (i + 1) + " 章"
                    + (StringUtils.hasText(chapter.getTitle()) ? "｜" + chapter.getTitle().trim() : "");
            String content = StringUtils.hasText(chapter.getContent())
                    ? chapter.getContent().trim()
                    : "（本章尚未开写）";
            sections.add(AgentContextSection.optional("原文", label, content,
                    PRIORITY_CHAPTER_BASE + distance, CHAPTER_POLICY));
        }
        return sections;
    }

    // ==================== 取数细节 ====================

    /**
     * 章节正序（第 1 章在前）。
     *
     * <p>这里直接用 mapper 而不是 {@code ChapterService.list()}：后者刻意不加载正文大字段
     * （列表页不需要），而取数器恰恰需要正文。
     */
    private List<Chapter> orderedChapters(Long projectId) {
        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, projectId));
        chapters.sort(Comparator.comparing(Chapter::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return chapters;
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
