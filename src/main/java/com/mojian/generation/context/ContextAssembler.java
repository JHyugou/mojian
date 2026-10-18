package com.mojian.generation.context;

import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.project.entity.StoryProject;
import com.mojian.world.character.entity.CharacterNode;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.setting.entity.SettingEntry;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 上下文组装引擎（FR-005 / FR-011）——纯函数核心，便于单测。
 *
 * 组装规则：
 * 1. 人物：只取启用档案；蓝图节点明确引用者优先，其余按创建顺序补足，上限 6 人；
 *    空字段一律不注入。
 * 2. 设定：先按"适用章节范围"过滤，再按触发关键词命中与否、优先级排序，上限 8 条；
 *    每条内容截断至 500 字。
 * 3. 记忆：取本章之前最近 3 章的章节记忆。
 * 4. 系统不得无条件注入全部资料——所有列表都有容量上限。
 * 5. 现有正文随任务快照保存：续写衔接、改写语境、记忆提炼都依赖它；
 *    超过上限时保留首尾、省略中段（保护模型上下文窗口）。
 * 6. 人物版本节点（起点制）：生成第 N 章时，若人物存在 chapter_from ≤ N 的节点，
 *    取最近一个节点的字段覆盖基础档案（节点字段 null 表示沿用基础档案）。
 */
@Component
public class ContextAssembler {

    /*
     * 全部解开（2026-10）。
     *
     * 这里原有一整套"容量上限"：人物最多 6 个、设定最多 8 条且每条截到 500 字、
     * 正文超过 2 万字保留首尾省略中段、上一章只取尾部 4 千字。
     * 那套数字是按十几万上下文算的，在当时是必要的。
     *
     * 但现在的模型上下文以百万计，而这部小说的全部资料（含 13 章正文）加起来
     * 才十万 token 出头 —— 也就是说，这些上限实际起的作用是：
     * **从足以装下全书的预算里，硬生生裁掉模型最需要的那部分。**
     *
     * 特别明显的三处代价：
     * - 人物只取 6 个：写到中盘，一个重要角色可能"凭空消失"，模型只能凭印象编
     * - 设定每条 500 字：世界观规则被砍成半句，模型只能猜
     * - 上一章只留尾部 4 千字：衔接点之前的铺垫全丢了
     *
     * 所以把它们都设成"实际不会触发的值"而不是删掉代码：
     * （用 100 万而不是 Integer.MAX_VALUE —— 后者一旦被某处当成长度去做分配，
     *  会直接 OutOfMemory。测试里就踩过一次。）
     * 保留这些分支作为异常超长内容的最后一道防御（真遇到病态数据不至于把请求撑爆），
     * 但正常写作永远碰不到它们。
     */
    static final int MAX_CHARACTERS = 1_000_000;
    static final int MAX_SETTINGS = 1_000_000;

    /** 对话示例草稿的世界观注入上限（现已放开） */
    static final int MAX_DIALOGUE_SETTINGS = 1_000_000;

    /** 对话示例草稿的其他人物注入上限（现已放开） */
    static final int MAX_DIALOGUE_OTHERS = 1_000_000;
    static final int SETTING_CONTENT_MAX_CHARS = 1_000_000;

    /** 现有正文注入上限（现已放开，仅保留分支防御病态超长内容） */
    static final int CURRENT_CONTENT_MAX_CHARS = 1_000_000;

    /** 上一章正文注入长度（现已放开，整章注入） */
    static final int PREVIOUS_TAIL_CHARS = 1_000_000;

    public ContextSnapshot buildSnapshot(StoryProject project,
                                         Chapter chapter,
                                         int chapterNo,
                                         PlotOutline outlineNode,
                                         List<Long> referencedCharacterIds,
                                         List<CharacterProfile> allCharacters,
                                         List<SettingEntry> allSettings,
                                         List<Chapter> orderedChapters,
                                         Map<Long, ChapterMemory> memoriesByChapterId,
                                         String instruction,
                                         String selection,
                                         String confirmedPlan,
                                         Map<Long, List<CharacterNode>> appliedNodes,
                                         String previousContentTail) {
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setProjectName(project.getName());
        snapshot.setStyleRules(mergeStyleRules(project));
        snapshot.setChapterNo(chapterNo);
        snapshot.setChapterTitle(chapter.getTitle());
        snapshot.setInstruction(trimOrNull(instruction));
        snapshot.setSelection(trimOrNull(selection));
        extractSelectionContext(chapter.getContent(), trimOrNull(selection), snapshot);
        snapshot.setConfirmedPlan(trimOrNull(confirmedPlan));
        snapshot.setCurrentContent(condenseContent(chapter.getContent()));
        snapshot.setOutline(toOutlineBrief(outlineNode));
        snapshot.setCharacters(selectCharacters(allCharacters, referencedCharacterIds).stream()
                .map(c -> this.toCharacterBrief(c,
                        appliedNodes == null ? null : appliedNodes.get(c.getId())))
                .toList());
        snapshot.setSettings(selectSettings(allSettings, matchText(snapshot, outlineNode), chapterNo).stream()
                .map(this::toSettingBrief).toList());
        snapshot.setSettingsConsidered((int) allSettings.stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled())).count());
        snapshot.setPreviousMemories(selectMemories(orderedChapters, chapter.getId(), memoriesByChapterId));
        snapshot.setPreviousContentTail(trimOrNull(previousContentTail));
        return snapshot;
    }

    /** 人物筛选：启用的优先取蓝图引用者，再按创建顺序补足，空字段不注入 */
    List<CharacterProfile> selectCharacters(List<CharacterProfile> all, List<Long> referencedIds) {
        List<CharacterProfile> enabled = all.stream()
                .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                .toList();
        List<CharacterProfile> result = new ArrayList<>();
        Set<Long> taken = new HashSet<>();
        for (Long id : referencedIds) {
            enabled.stream()
                    .filter(c -> c.getId().equals(id) && taken.add(c.getId()))
                    .findFirst()
                    .ifPresent(result::add);
        }
        enabled.stream()
                .filter(c -> !taken.contains(c.getId()))
                .sorted(Comparator.comparing(c -> Optional.ofNullable(c.getCreatedAt()).orElse(LocalDateTime.MIN)))
                .limit(MAX_CHARACTERS - result.size())
                .forEach(result::add);
        return result.size() > MAX_CHARACTERS ? result.subList(0, MAX_CHARACTERS) : result;
    }

    /** 设定筛选：章节范围过滤 → 关键词命中优先 → 优先级 → 截断容量 */
    List<SettingEntry> selectSettings(List<SettingEntry> all, String matchText, int chapterNo) {
        String text = matchText == null ? "" : matchText.toLowerCase();
        return all.stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .filter(s -> scopeContains(s.getChapterScope(), chapterNo))
                .sorted(Comparator
                        .comparing((SettingEntry s) -> keywordHit(s, text) ? 0 : 1)
                        .thenComparing(s -> Optional.ofNullable(s.getPriority()).orElse(0), Comparator.reverseOrder())
                        .thenComparing(s -> Optional.ofNullable(s.getCreatedAt()).orElse(LocalDateTime.MIN)))
                .limit(MAX_SETTINGS)
                .toList();
    }

    /**
     * 对话示例草稿的世界观注入（FR-018 增强）：人物卡是全书素材，不涉章节范围，
     * 只按"触发关键词命中 → 优先级 → 创建时间"选取与该人物相关的启用设定。
     * matchText 通常是 人物名 + 身份 + 设定全文 + 补充要求。
     */
    public List<SettingEntry> selectSettingsForCharacterDialogue(List<SettingEntry> all, String matchText) {
        String text = matchText == null ? "" : matchText.toLowerCase();
        return all.stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .sorted(Comparator
                        .comparing((SettingEntry s) -> keywordHit(s, text) ? 0 : 1)
                        .thenComparing(s -> Optional.ofNullable(s.getPriority()).orElse(0), Comparator.reverseOrder())
                        .thenComparing(s -> Optional.ofNullable(s.getCreatedAt()).orElse(LocalDateTime.MIN)))
                .limit(MAX_DIALOGUE_SETTINGS)
                .toList();
    }

    /**
     * 对话示例草稿的其他人物注入：项目内除主角外的启用人物，
     * 姓名出现在主角设定全文中（关系人）的排前，其余按创建顺序，上限 6。
     */
    public List<CharacterProfile> selectOthersForCharacterDialogue(
            List<CharacterProfile> all, Long selfId, String subjectDescription) {
        String subject = subjectDescription == null ? "" : subjectDescription;
        return all.stream()
                .filter(c -> Boolean.TRUE.equals(c.getEnabled()))
                .filter(c -> !c.getId().equals(selfId))
                .sorted(Comparator
                        .comparing((CharacterProfile c) ->
                                (StringUtils.hasText(c.getName()) && subject.contains(c.getName())) ? 0 : 1)
                        .thenComparing(c -> Optional.ofNullable(c.getCreatedAt()).orElse(LocalDateTime.MIN)))
                .limit(MAX_DIALOGUE_OTHERS)
                .toList();
    }

    /** 章节范围解析："3-30"、"1,5,9-12"；空白表示全部适用 */
    boolean scopeContains(String scope, int chapterNo) {
        if (!StringUtils.hasText(scope)) {
            return true;
        }
        for (String segment : scope.split("[,，]")) {
            String part = segment.trim();
            if (part.isEmpty()) {
                continue;
            }
            int dash = part.indexOf('-');
            try {
                if (dash < 0) {
                    if (Integer.parseInt(part) == chapterNo) {
                        return true;
                    }
                } else {
                    int from = Integer.parseInt(part.substring(0, dash).trim());
                    int to = Integer.parseInt(part.substring(dash + 1).trim());
                    if (chapterNo >= from && chapterNo <= to) {
                        return true;
                    }
                }
            } catch (NumberFormatException ignored) {
                // 非法片段视为不匹配，DTO 层 @Pattern 已做前置校验
            }
        }
        return false;
    }

    private boolean keywordHit(SettingEntry setting, String lowerText) {
        if (!StringUtils.hasText(setting.getTriggerKeywords()) || lowerText.isEmpty()) {
            return false;
        }
        return Arrays.stream(setting.getTriggerKeywords().split("[,，]"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .anyMatch(k -> lowerText.contains(k.toLowerCase()));
    }

    /** 匹配文本：章节标题 + 蓝图摘要/目标 + 创作指令（FR-005 提取候选关键词的语料） */
    private String matchText(ContextSnapshot snapshot, PlotOutline outlineNode) {
        StringBuilder sb = new StringBuilder();
        if (snapshot.getChapterTitle() != null) {
            sb.append(snapshot.getChapterTitle()).append('\n');
        }
        if (outlineNode != null) {
            appendIfPresent(sb, outlineNode.getSummary());
        }
        if (snapshot.getInstruction() != null) {
            sb.append(snapshot.getInstruction());
        }
        return sb.toString();
    }

    /**
     * 改写/扩缩写时提取选中文本前后的上下文片段（各约 800 字），
     * 让模型看到选中段落的前后文，保持改写后逻辑连贯。
     */
    private void extractSelectionContext(String content, String selection, ContextSnapshot snapshot) {
        if (!StringUtils.hasText(selection) || !StringUtils.hasText(content)) {
            return;
        }
        int selStart = content.indexOf(selection);
        if (selStart < 0) {
            return;
        }
        int selEnd = selStart + selection.length();
        int ctxLen = 800;
        int before = Math.max(0, selStart - ctxLen);
        int after = Math.min(content.length(), selEnd + ctxLen);
        snapshot.setBeforeSelection(content.substring(Math.max(0, before), selStart));
        snapshot.setAfterSelection(content.substring(selEnd, Math.min(content.length(), after)));
    }

    /**
     * 全书记忆注入：不设章数上限——每条记忆是浓缩信号（记忆全文），
     * 全部注入的成本远低于全文正文，却保证任何伏笔都不出窗口。
     */
    private List<ContextSnapshot.MemoryBrief> selectMemories(List<Chapter> orderedChapters,
                                                             Long currentChapterId,
                                                             Map<Long, ChapterMemory> memoriesByChapterId) {
        List<ContextSnapshot.MemoryBrief> result = new ArrayList<>();
        for (Chapter chapter : orderedChapters) {
            if (chapter.getId().equals(currentChapterId)) {
                break;
            }
            ChapterMemory memory = memoriesByChapterId.get(chapter.getId());
            if (memory == null || !StringUtils.hasText(memory.getMemory())) {
                continue;
            }
            ContextSnapshot.MemoryBrief brief = new ContextSnapshot.MemoryBrief();
            brief.setChapterTitle(chapter.getTitle());
            brief.setMemory(memory.getMemory());
            result.add(brief);
        }
        return result;
    }

    /** 上一章正文尾部：空内容返回 null */
    public String previousChapterTail(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String trimmed = content.trim();
        return trimmed.length() <= PREVIOUS_TAIL_CHARS
                ? trimmed
                : trimmed.substring(trimmed.length() - PREVIOUS_TAIL_CHARS);
    }

    /**
     * 组装人物简报：基础档案打底；若存在对本章生效的版本节点，
     * 节点字段非 null 时逐项覆盖（null = 沿用基础档案，传空字符串 = 有意清空）。
     */
    private ContextSnapshot.CharacterBrief toCharacterBrief(CharacterProfile c, List<CharacterNode> nodes) {
        ContextSnapshot.CharacterBrief brief = new ContextSnapshot.CharacterBrief();
        brief.setName(c.getName());
        brief.setRoleIdentity(c.getRoleIdentity());
        brief.setDescription(c.getDescription());
        brief.setExampleDialogue(c.getExampleDialogue());
        if (nodes != null && !nodes.isEmpty()) {
            // 增量时间线：节点是"自第X章起"的变化补充，按起点升序追加在基础设定之后；
            // 写早期章节时未来节点不注入，历史节点永不覆盖基础档案
            StringBuilder timeline = new StringBuilder(brief.getDescription() == null
                    ? "" : brief.getDescription());
            for (CharacterNode node : nodes) {
                if (StringUtils.hasText(node.getDescription())) {
                    timeline.append("\n【自第").append(node.getChapterFrom()).append("章起】")
                            .append(node.getDescription().trim());
                }
            }
            brief.setDescription(timeline.toString());
        }
        return brief;
    }

    private ContextSnapshot.SettingBrief toSettingBrief(SettingEntry s) {
        ContextSnapshot.SettingBrief brief = new ContextSnapshot.SettingBrief();
        brief.setTitle(s.getTitle());
        String content = s.getContent() == null ? "" : s.getContent();
        brief.setContent(content.length() > SETTING_CONTENT_MAX_CHARS
                ? content.substring(0, SETTING_CONTENT_MAX_CHARS) + "…"
                : content);
        return brief;
    }

    private ContextSnapshot.OutlineBrief toOutlineBrief(PlotOutline node) {
        if (node == null) {
            return null;
        }
        ContextSnapshot.OutlineBrief brief = new ContextSnapshot.OutlineBrief();
        brief.setName(node.getName());
        brief.setSummary(node.getSummary());
        return brief;
    }

    private String mergeStyleRules(StoryProject project) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(project.getStyle())) {
            parts.add("文风：" + project.getStyle());
        }
        if (StringUtils.hasText(project.getWritingRules())) {
            parts.add("写作规则：" + project.getWritingRules());
        }
        return parts.isEmpty() ? null : String.join("；", parts);
    }

    private void appendIfPresent(StringBuilder sb, String value) {
        if (StringUtils.hasText(value)) {
            sb.append(value).append('\n');
        }
    }

    private String trimOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 现有正文压缩：超上限时保留头部与尾部、中段以省略标记替代，
     * 压缩结果恒不超过 CURRENT_CONTENT_MAX_CHARS。
     * 首尾全保是因为开头是续写衔接处、结尾贴近"接下来写什么"；中段被省略时，
     * 改写任务若选中文本恰好落在省略区，模型缺少该处语境，但替换仍在后端按原文精确执行。
     */
    String condenseContent(String content) {
        if (content == null || content.length() <= CURRENT_CONTENT_MAX_CHARS) {
            return content;
        }
        int reserved = 64; // 省略标记及换行预留
        int bodyBudget = CURRENT_CONTENT_MAX_CHARS - reserved;
        int head = bodyBudget * 7 / 10;
        int tail = bodyBudget - head;
        return content.substring(0, head)
                + "\n……（中间约 " + (content.length() - head - tail) + " 字已省略）……\n"
                + content.substring(content.length() - tail);
    }
}
