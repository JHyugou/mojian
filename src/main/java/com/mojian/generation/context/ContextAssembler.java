package com.mojian.generation.context;

import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.project.entity.StoryProject;
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
 */
@Component
public class ContextAssembler {

    static final int MAX_CHARACTERS = 6;
    static final int MAX_SETTINGS = 8;
    static final int MAX_MEMORY_CHAPTERS = 3;
    static final int SETTING_CONTENT_MAX_CHARS = 500;

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
                                         String confirmedPlan) {
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setProjectName(project.getName());
        snapshot.setStyleRules(mergeStyleRules(project));
        snapshot.setChapterNo(chapterNo);
        snapshot.setChapterTitle(chapter.getTitle());
        snapshot.setInstruction(trimOrNull(instruction));
        snapshot.setSelection(trimOrNull(selection));
        snapshot.setConfirmedPlan(trimOrNull(confirmedPlan));
        snapshot.setOutline(toOutlineBrief(outlineNode));
        snapshot.setCharacters(selectCharacters(allCharacters, referencedCharacterIds).stream()
                .map(this::toCharacterBrief).toList());
        snapshot.setSettings(selectSettings(allSettings, matchText(snapshot, outlineNode), chapterNo).stream()
                .map(this::toSettingBrief).toList());
        snapshot.setSettingsConsidered((int) allSettings.stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled())).count());
        snapshot.setPreviousMemories(selectMemories(orderedChapters, chapter.getId(), memoriesByChapterId));
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
            appendIfPresent(sb, outlineNode.getPlotGoal());
        }
        if (snapshot.getInstruction() != null) {
            sb.append(snapshot.getInstruction());
        }
        return sb.toString();
    }

    private List<ContextSnapshot.MemoryBrief> selectMemories(List<Chapter> orderedChapters,
                                                             Long currentChapterId,
                                                             Map<Long, ChapterMemory> memoriesByChapterId) {
        List<ContextSnapshot.MemoryBrief> result = new ArrayList<>();
        for (Chapter chapter : orderedChapters) {
            if (chapter.getId().equals(currentChapterId)) {
                break;
            }
            ChapterMemory memory = memoriesByChapterId.get(chapter.getId());
            if (memory == null || !StringUtils.hasText(memory.getSummary())) {
                continue;
            }
            ContextSnapshot.MemoryBrief brief = new ContextSnapshot.MemoryBrief();
            brief.setChapterTitle(chapter.getTitle());
            brief.setSummary(memory.getSummary());
            brief.setUnresolvedConflicts(memory.getUnresolvedConflicts());
            result.add(brief);
        }
        return result.size() > MAX_MEMORY_CHAPTERS
                ? result.subList(result.size() - MAX_MEMORY_CHAPTERS, result.size())
                : result;
    }

    private ContextSnapshot.CharacterBrief toCharacterBrief(CharacterProfile c) {
        ContextSnapshot.CharacterBrief brief = new ContextSnapshot.CharacterBrief();
        brief.setName(c.getName());
        brief.setRoleIdentity(c.getRoleIdentity());
        brief.setPersonality(c.getPersonality());
        brief.setCoreGoal(c.getCoreGoal());
        brief.setMainConflict(c.getMainConflict());
        brief.setRelationships(c.getRelationships());
        brief.setSpeechStyle(c.getSpeechStyle());
        brief.setExampleDialogue(c.getExampleDialogue());
        brief.setCurrentStatus(c.getCurrentStatus());
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
        brief.setPlotGoal(node.getPlotGoal());
        brief.setConflict(node.getConflict());
        brief.setScene(node.getScene());
        brief.setMustEvents(node.getMustEvents());
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
}
