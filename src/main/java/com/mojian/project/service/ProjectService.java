package com.mojian.project.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.chapter.mapper.ChapterMapper;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.mapper.GenerationTaskMapper;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.entity.StoryProject;
import com.mojian.project.mapper.StoryProjectMapper;
import com.mojian.version.mapper.ChapterVersionMapper;
import com.mojian.world.character.mapper.CharacterProfileMapper;
import com.mojian.world.setting.mapper.SettingEntryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService extends ServiceImpl<StoryProjectMapper, StoryProject> {

    private final CharacterProfileMapper characterProfileMapper;
    private final SettingEntryMapper settingEntryMapper;
    private final com.mojian.outline.mapper.PlotOutlineMapper plotOutlineMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterMemoryMapper chapterMemoryMapper;
    private final GenerationTaskMapper generationTaskMapper;
    private final ChapterVersionMapper chapterVersionMapper;

    public List<StoryProject> list() {
        return lambdaQuery().orderByDesc(StoryProject::getCreatedAt).list();
    }

    public StoryProject create(ProjectSaveRequest request) {
        StoryProject project = new StoryProject();
        BeanUtils.copyProperties(request, project);
        save(project);
        return project;
    }

    public StoryProject update(Long projectId, ProjectSaveRequest request) {
        StoryProject project = requireById(projectId);
        BeanUtils.copyProperties(request, project);
        updateById(project);
        return project;
    }

    /**
     * 删除项目并同时清理其全部从属数据（FR-002）。
     * 任何一个删除失败整体回滚，不产生半删除状态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long projectId) {
        requireById(projectId);

        List<Chapter> chapters = chapterMapper.selectByProjectId(projectId);
        for (Chapter chapter : chapters) {
            chapterMemoryMapper.deleteByChapterId(chapter.getId());
            chapterVersionMapper.deleteByChapterId(chapter.getId());
            generationTaskMapper.deleteByChapterId(chapter.getId());
        }
        chapterMapper.deleteByProjectId(projectId);
        characterProfileMapper.deleteByProjectId(projectId);
        settingEntryMapper.deleteByProjectId(projectId);
        plotOutlineMapper.deleteByProjectId(projectId);
        removeById(projectId);
    }

    public StoryProject requireById(Long projectId) {
        StoryProject project = getById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "创作项目不存在");
        }
        return project;
    }
}
