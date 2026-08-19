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
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

        return assembler.buildSnapshot(
                project, chapter, chapterNo, outlineNode,
                parseReferencedIds(outlineNode),
                characterService.list(projectId),
                settingService.list(projectId, null),
                orderedChapters, memories,
                instruction, selection, confirmedPlan);
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
