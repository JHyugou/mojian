package com.mojian.chapter.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mojian.chapter.dto.ChapterMemorySaveRequest;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.mapper.ChapterMapper;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.mapper.GenerationTaskMapper;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import com.mojian.project.service.ProjectService;
import com.mojian.version.mapper.ChapterVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChapterService extends ServiceImpl<ChapterMapper, Chapter> {

    private final ChapterMemoryMapper chapterMemoryMapper;
    private final GenerationTaskMapper generationTaskMapper;
    private final ChapterVersionMapper chapterVersionMapper;
    private final ProjectService projectService;
    private final OutlineService outlineService;

    /**
     * 章节列表：不加载正文大字段，仅返回概要信息。
     */
    public List<Chapter> list(Long projectId) {
        projectService.requireById(projectId);
        return lambdaQuery()
                .select(Chapter.class, f -> !"content".equals(f.getColumn()))
                .eq(Chapter::getProjectId, projectId)
                .orderByAsc(Chapter::getCreatedAt)
                .list();
    }

    public Chapter create(Long projectId, ChapterSaveRequest request) {
        projectService.requireById(projectId);
        if (request.getOutlineNodeId() != null) {
            requireOutlineNodeInProject(projectId, request.getOutlineNodeId());
        }
        Chapter chapter = new Chapter();
        BeanUtils.copyProperties(request, chapter);
        chapter.setProjectId(projectId);
        chapter.setCurrentVersionNo(0);
        chapter.setStatus(StringUtils.hasText(request.getStatus()) ? request.getStatus() : "DRAFT");
        save(chapter);
        return chapter;
    }

    public Chapter get(Long chapterId) {
        return requireById(chapterId);
    }

    public Chapter update(Long chapterId, ChapterSaveRequest request) {
        Chapter chapter = requireById(chapterId);
        if (request.getOutlineNodeId() != null) {
            requireOutlineNodeInProject(chapter.getProjectId(), request.getOutlineNodeId());
        }
        BeanUtils.copyProperties(request, chapter);
        if (!StringUtils.hasText(chapter.getStatus())) {
            chapter.setStatus("DRAFT");
        }
        updateById(chapter);
        return chapter;
    }

    /**
     * 删除章节及其记忆、版本和任务记录，事务保证不残留孤儿数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long chapterId) {
        requireById(chapterId);
        chapterMemoryMapper.deleteByChapterId(chapterId);
        chapterVersionMapper.deleteByChapterId(chapterId);
        generationTaskMapper.deleteByChapterId(chapterId);
        removeById(chapterId);
    }

    public ChapterMemory getMemory(Long chapterId) {
        requireById(chapterId);
        ChapterMemory memory = chapterMemoryMapper.selectByChapterId(chapterId);
        if (memory == null) {
            memory = new ChapterMemory();
            memory.setChapterId(chapterId);
        }
        return memory;
    }

    public ChapterMemory saveMemory(Long chapterId, ChapterMemorySaveRequest request) {
        requireById(chapterId);
        ChapterMemory memory = chapterMemoryMapper.selectByChapterId(chapterId);
        if (memory == null) {
            memory = new ChapterMemory();
            memory.setChapterId(chapterId);
            BeanUtils.copyProperties(request, memory);
            chapterMemoryMapper.insert(memory);
        } else {
            BeanUtils.copyProperties(request, memory);
            chapterMemoryMapper.updateById(memory);
        }
        return memory;
    }

    public Chapter requireById(Long chapterId) {
        Chapter chapter = getById(chapterId);
        if (chapter == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节不存在");
        }
        return chapter;
    }

    private void requireOutlineNodeInProject(Long projectId, Long outlineNodeId) {
        PlotOutline node = outlineService.requireById(outlineNodeId);
        if (!node.getProjectId().equals(projectId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "蓝图节点不属于当前项目");
        }
    }
}
