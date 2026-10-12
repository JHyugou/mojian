package com.mojian.generation.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.chapter.service.ChapterService;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文数据装配：把数据库中的资料交给纯函数组装器（FR-011）。
 * 查询全部走已有 Service/Mapper，单用户数据量下全量加载后内存筛选。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationContextService {

    private final ProjectService projectService;
    private final CharacterService characterService;
    private final SettingService settingService;
    private final OutlineService outlineService;
    private final ChapterService chapterService;
    private final ChapterMemoryMapper chapterMemoryMapper;
    private final CharacterNodeMapper characterNodeMapper;
    private final ContextAssembler assembler;
    private final ObjectMapper objectMapper;

    public ContextSnapshot buildContext(Chapter chapter,
                                        String instruction,
                                        String selection,
                                        String confirmedPlan) {
        Long projectId = chapter.getProjectId();
        StoryProject project = projectService.requireById(projectId);

        List<Chapter> orderedChapters = chapterService.list(projectId);
        int chapterNo = 1;
        for (int i = 0; i < orderedChapters.size(); i++) {
            if (orderedChapters.get(i).getId().equals(chapter.getId())) {
                chapterNo = i + 1;
                break;
            }
        }

        PlotOutline outlineNode = chapter.getOutlineNodeId() == null
                ? null
                : outlineService.requireById(chapter.getOutlineNodeId());

        Map<Long, ChapterMemory> memories = new HashMap<>();
        for (Chapter c : orderedChapters) {
            ChapterMemory memory = chapterMemoryMapper.selectByChapterId(c.getId());
            if (memory != null) {
                memories.put(c.getId(), memory);
            }
        }

        List<CharacterProfile> characters = characterService.list(projectId);
        Map<Long, List<CharacterNode>> appliedNodes = nodesAppliedBy(characters, chapterNo);
        String previousTail = chapterNo > 1 && chapterNo <= orderedChapters.size()
                ? assembler.previousChapterTail(
                        chapterService.requireById(orderedChapters.get(chapterNo - 2).getId()).getContent())
                : null;

        return assembler.buildSnapshot(
                project, chapter, chapterNo, outlineNode,
                parseReferencedIds(outlineNode),
                characters,
                settingService.list(projectId, null),
                orderedChapters, memories,
                instruction, selection, confirmedPlan, appliedNodes, previousTail);
    }

    /**
     * 增量时间线解析（FR-003 演进版）：每个人物取 chapter_from ≤ 本章的**全部**节点，
     * 按起点升序返回——装配器把它们作为"自第X章起的变化"依序追加在基础设定之后。
     * 未来节点（chapter_from > 本章）不注入：写早期章节时不会剧透后期演变。
     * 返回的 Map 不含无生效节点的人物（沿用基础档案）。
     */
    private Map<Long, List<CharacterNode>> nodesAppliedBy(List<CharacterProfile> characters,
                                                          int chapterNo) {
        if (characters.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = characters.stream().map(CharacterProfile::getId).toList();
        Map<Long, List<CharacterNode>> applied = new HashMap<>();
        for (CharacterNode node : characterNodeMapper.selectByCharacterIds(ids)) {
            if (node.getChapterFrom() != null && node.getChapterFrom() <= chapterNo) {
                applied.computeIfAbsent(node.getCharacterId(), k -> new ArrayList<>()).add(node);
            }
        }
        return applied;
    }

    /** 蓝图章节节点上记录的主要人物 ID（JSON 数组字符串） */
    private List<Long> parseReferencedIds(PlotOutline outlineNode) {
        if (outlineNode == null || outlineNode.getMainCharacters() == null
                || outlineNode.getMainCharacters().isBlank()) {
            return List.of();
        }
        try {
            Long[] ids = objectMapper.readValue(outlineNode.getMainCharacters(), Long[].class);
            return List.of(ids);
        } catch (Exception e) {
            log.warn("蓝图节点主要人物字段解析失败，按无引用处理: {}", e.getMessage());
            return List.of();
        }
    }
}
