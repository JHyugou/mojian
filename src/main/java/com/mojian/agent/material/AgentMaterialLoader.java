package com.mojian.agent.material;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mojian.agent.context.AgentContextSection;
import com.mojian.agent.context.ContextSectionPolicy;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.agent.entity.AgentDraft;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.mapper.PlotOutlineMapper;
import com.mojian.project.entity.StoryProject;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.entity.CharacterNode;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.mapper.CharacterNodeMapper;
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
    /**
     * 待确认草稿。
     *
     * <p>排得很前：它是"我上一轮刚跟你提的方案"，比任何背景资料都更该先看到。
     * 拿不到它的后果很具体 —— 换个对话，Agent 会反问"杜渐是谁"，
     * 而那明明是它自己刚起草的。
     */
    public static final int PRIORITY_DRAFT = 12;
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
    private final CharacterNodeMapper characterNodeMapper;
    private final SettingService settingService;
    private final ProjectService projectService;
    private final ChapterIndex chapterIndex;
    private final ChapterMemoryMapper chapterMemoryMapper;
    private final PlotOutlineMapper plotOutlineMapper;
    private final com.mojian.agent.mapper.AgentDraftMapper draftMapper;

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
        return loadAll(projectId, excludeChapterId, null);
    }

    /**
     * 取全部材料，并排除当前章节与本章蓝图节点。
     *
     * <p>{@code excludeOutlineNodeId} 是 writer 传进来的：本章蓝图会作为 REQUIRED 材料
     * 单独注入，如果"已有蓝图"里再来一遍，模型会看到两份内容略有出入的任务书。
     */
    public List<AgentContextSection> loadAll(Long projectId, Long excludeChapterId, Long excludeOutlineNodeId) {
        /*
         * 先取一次按时间排好序的章节，并定位"当前章"。
         *
         * 这两个信息决定两件很容易被忽略的事：
         *  - 写作时只能注入"当前章之前"的章节与前情摘要，后面的章节是未来，不能提前塞给模型；
         *  - 人物版本节点按起点制生效，写作第 N 章时应使用 chapterFrom <= N 的版本。
         * 之前没有这层判断，改早期章节时会把后面十几章当"前文"注入，模型看到的
         * 时间线是错的 —— 这类错误不报异常，只表现为"它怎么写出剧透了"。
         */
        List<Chapter> orderedChapters = chapterIndex.ordered(projectId);
        Integer currentChapterNo = currentChapterNo(orderedChapters, excludeChapterId);
        List<AgentContextSection> sections = new ArrayList<>();
        // 概况放最前：书名、题材、文风、写作规则 —— 这是"这本书是什么"，
        // 比任何细节都更该先看到。漏了它，Agent 就会在完全不知道题材的情况下
        // 追问"你要写玄幻还是都市"，而作者明明已经在项目里填过了。
        addIfPresent(sections, projectSection(projectId));
        addIfPresent(sections, draftSection(projectId));
        addIfPresent(sections, memorySection(orderedChapters, currentChapterNo));
        addIfPresent(sections, outlineSection(projectId, excludeOutlineNodeId));
        addIfPresent(sections, characterSection(projectId, currentChapterNo));
        addIfPresent(sections, settingSection(projectId));
        sections.addAll(chapterSections(orderedChapters, currentChapterNo));
        log.debug("取数完成：项目 {} 共 {} 块材料，合计约 {} token",
                projectId, sections.size(),
                sections.stream().mapToInt(AgentContextSection::estimateTokens).sum());
        return sections;
    }

    /** 当前章是第几章（从 1 开始）；不是写作场景（没有 excludeChapterId）时返回 null */
    private Integer currentChapterNo(List<Chapter> orderedChapters, Long excludeChapterId) {
        if (excludeChapterId == null) {
            return null;
        }
        for (int i = 0; i < orderedChapters.size(); i++) {
            if (excludeChapterId.equals(orderedChapters.get(i).getId())) {
                return i + 1;
            }
        }
        return null;
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

    /**
     * 人物档案 + 生效的人物版本节点。
     *
     * @param currentChapterNo 当前写第几章；null 表示策划场景（没有"当前章"），
     *                         此时把所有版本节点按时间线一并给出，便于规划全局人物弧光
     */
    private AgentContextSection characterSection(Long projectId, Integer currentChapterNo) {
        List<CharacterProfile> characters = characterService.list(projectId).stream()
                .filter(c -> !Boolean.FALSE.equals(c.getEnabled()))
                .toList();
        if (characters.isEmpty()) {
            return null;
        }

        // 一次查完所有人物节点，避免按人物逐个查库
        Map<Long, List<CharacterNode>> nodesByCharacter = characterNodeMapper
                .selectByCharacterIds(characters.stream().map(CharacterProfile::getId).toList())
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(CharacterNode::getCharacterId));

        StringBuilder sb = new StringBuilder();
        for (CharacterProfile character : characters) {
            sb.append("■ ").append(character.getName() == null ? "未命名" : character.getName().trim());
            if (StringUtils.hasText(character.getRoleIdentity())) {
                sb.append("｜").append(character.getRoleIdentity().trim());
            }
            sb.append('\n');

            List<CharacterNode> nodes = nodesByCharacter.getOrDefault(character.getId(), List.of()).stream()
                    .filter(node -> node.getChapterFrom() != null)
                    .sorted(java.util.Comparator.comparing(CharacterNode::getChapterFrom))
                    .toList();

            if (currentChapterNo == null) {
                // 策划场景：基础设定 + 完整演变时间线，便于规划人物弧光
                if (StringUtils.hasText(character.getDescription())) {
                    sb.append(character.getDescription().trim()).append('\n');
                }
                for (CharacterNode node : nodes) {
                    if (!StringUtils.hasText(node.getDescription())) {
                        continue;
                    }
                    sb.append("  【自第").append(node.getChapterFrom()).append("章起】")
                            .append(node.getDescription().trim()).append('\n');
                }
            } else {
                // 写作场景：只给"当前生效"的状态。版本节点的 description 是全文快照，
                // 把历史版本也塞进去只会让模型在两套设定之间摇摆。
                List<CharacterNode> effective = nodes.stream()
                        .filter(node -> node.getChapterFrom() <= currentChapterNo)
                        .toList();
                if (!effective.isEmpty()) {
                    CharacterNode latest = effective.get(effective.size() - 1);
                    sb.append("【当前状态 · 自第").append(latest.getChapterFrom()).append("章起】")
                            .append(StringUtils.hasText(latest.getDescription())
                                    ? latest.getDescription().trim() : "（版本节点未填写内容）")
                            .append('\n');
                } else if (StringUtils.hasText(character.getDescription())) {
                    sb.append(character.getDescription().trim()).append('\n');
                }
            }
            sb.append('\n');
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
    private AgentContextSection memorySection(List<Chapter> chapters, Integer currentChapterNo) {
        if (chapters.isEmpty()) {
            return null;
        }
        Map<Long, String> memories = memoriesByChapter(
                chapters.stream().map(Chapter::getId).toList());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chapters.size(); i++) {
            // 写作场景只给"已经写过的章"的前情；当前章及之后的记忆不能提前注入
            if (currentChapterNo != null && i + 1 >= currentChapterNo) {
                break;
            }
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
     * <p><b>写作场景只取当前章之前的章节</b>：改第 5 章时，第 6~13 章属于"未来"，
     * 把它们当前文注入会让模型看到一条错误的时间线（甚至剧透）。
     * {@code currentChapterNo == null} 表示策划/无当前章，取全部章节。
     */
    private List<AgentContextSection> chapterSections(List<Chapter> chapters, Integer currentChapterNo) {
        List<AgentContextSection> sections = new ArrayList<>();

        // orderedChapters 是正序（第 1 章在前），从后往前生成，最近的一章先入列。
        // distance 按"在结果里的位置"算，而不是按原始序号 —— 否则排除一章之后优先级会出现空洞
        int distance = 0;
        for (int i = chapters.size() - 1; i >= 0; i--) {
            if (currentChapterNo != null && i + 1 >= currentChapterNo) {
                continue; // 当前章及未来章不注入
            }
            Chapter chapter = chapters.get(i);
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

    /** 兼容旧调用：只按排除章取（没有"当前章"概念时取全部） */
    private List<AgentContextSection> chapterSections(Long projectId, Long excludeChapterId) {
        List<Chapter> ordered = chapterIndex.ordered(projectId);
        Integer currentNo = currentChapterNo(ordered, excludeChapterId);
        return chapterSections(ordered, currentNo);
    }

    // ==================== 取数细节 ====================

    // ==================== 已有蓝图 ====================

    /**
     * 已有蓝图树（缩进渲染）。
     *
     * <p>策划 Agent 必须先知道"已经规划到哪了"：否则它会重复规划已经定好的部分，
     * 或者新加的节点和已有结构前后矛盾。这是最容易漏、后果也最直接的一类材料。
     */
    /**
     * 本书待作者确认的草稿。
     *
     * <p><b>为什么草稿要进上下文</b>：草稿是 Agent 自己上一轮提的方案，
     * 但还没写进书里。不注入的话会出现一个很怪的现象 ——
     * 同一个项目、换一段新对话，它就完全不认识自己刚起草的角色了，
     * 会反问"杜渐是谁"。用户看到的是"它失忆了"。
     *
     * <p>草稿属于<b>整本书</b>，不是某一段对话的产物，所以按 projectId 查，
     * 而不是按 conversationId。
     *
     * <p>内容给全文（payloadJson 本身就是模型自己写的结构），
     * 不做摘要 —— 它要拿这个跟用户讨论"这条怎么改"，摘要反而会丢细节。
     */
    private AgentContextSection draftSection(Long projectId) {
        List<AgentDraft> pending = draftMapper.selectByProject(projectId).stream()
                .filter(draft -> "PENDING".equals(draft.getStatus()))
                .toList();
        if (pending.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (AgentDraft draft : pending) {
            sb.append("■ [草稿ID ").append(draft.getId()).append("] ")
                    .append(draft.getDraftType()).append("｜")
                    .append(draft.getTitle() == null ? "未命名" : draft.getTitle()).append('\n');
            if (StringUtils.hasText(draft.getAgentReason())) {
                sb.append("   起草理由：").append(draft.getAgentReason().trim()).append('\n');
            }
            if (StringUtils.hasText(draft.getPayloadJson())) {
                sb.append("   内容：").append(draft.getPayloadJson().trim()).append('\n');
            }
        }
        sb.append("\n以上草稿<b>还没进书</b>，正在等作者点确认。")
                .append("作者再提到它们时，直接按这里的内容讨论即可，不要说自己不知道。");

        return AgentContextSection.optional("草稿", "待作者确认的草稿", sb.toString().trim(),
                PRIORITY_DRAFT, ContextSectionPolicy.TRUNCATE_TO_FIT);
    }
    private AgentContextSection outlineSection(Long projectId, Long excludeNodeId) {
        List<PlotOutline> nodes = plotOutlineMapper.selectList(
                new LambdaQueryWrapper<PlotOutline>().eq(PlotOutline::getProjectId, projectId));
        if (excludeNodeId != null) {
            // 本章蓝图会以【本章蓝图】单独注入，这里不再重复（子节点仍保留，不会丢）
            nodes = nodes.stream().filter(node -> !excludeNodeId.equals(node.getId())).toList();
        }
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
