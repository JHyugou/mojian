package com.mojian.project;

import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.chapter.service.ChapterService;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.entity.StoryProject;
import com.mojian.project.service.ProjectService;
import com.mojian.version.entity.ChapterVersion;
import com.mojian.version.mapper.ChapterVersionMapper;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.dto.SettingSaveRequest;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProjectServiceTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private CharacterService characterService;
    @Autowired
    private SettingService settingService;
    @Autowired
    private ChapterService chapterService;
    @Autowired
    private com.mojian.world.character.mapper.CharacterProfileMapper characterMapper;
    @Autowired
    private com.mojian.world.setting.mapper.SettingEntryMapper settingMapper;
    @Autowired
    private com.mojian.chapter.mapper.ChapterMapper chapterMapper;
    @Autowired
    private ChapterMemoryMapper chapterMemoryMapper;
    @Autowired
    private ChapterVersionMapper chapterVersionMapper;

    private StoryProject newProject(String name) {
        ProjectSaveRequest req = new ProjectSaveRequest();
        req.setName(name);
        req.setGenre("玄幻");
        return projectService.create(req);
    }

    @Test
    void createAndListProject() {
        StoryProject project = newProject("墨笺测试项目");

        assertNotNull(project.getId());
        assertTrue(projectService.list().stream()
                .anyMatch(p -> p.getId().equals(project.getId())));
    }

    @Test
    void updateProject() {
        StoryProject project = newProject("旧名");
        ProjectSaveRequest req = new ProjectSaveRequest();
        req.setName("新名");
        req.setStyle("冷峻克制");

        projectService.update(project.getId(), req);

        assertEquals("新名", projectService.requireById(project.getId()).getName());
    }

    @Test
    void updateMissingProjectThrowsNotFound() {
        ProjectSaveRequest req = new ProjectSaveRequest();
        req.setName("任意");

        BusinessException e = assertThrows(BusinessException.class,
                () -> projectService.update(-1L, req));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), e.getErrorCode().getCode());
    }

    @Test
    void deleteProjectCascadesAllChildren() {
        StoryProject project = newProject("待删除项目");
        Long projectId = project.getId();

        CharacterSaveRequest characterReq = new CharacterSaveRequest();
        characterReq.setName("林晚照");
        CharacterProfile character = characterService.create(projectId, characterReq);

        SettingSaveRequest settingReq = new SettingSaveRequest();
        settingReq.setTitle("修真体系");
        settingReq.setContent("练气、筑基、金丹……");
        SettingEntry setting = settingService.create(projectId, settingReq);

        ChapterSaveRequest chapterReq = new ChapterSaveRequest();
        chapterReq.setTitle("第一章 夜雨");
        Long chapterId = chapterService.create(projectId, chapterReq).getId();

        ChapterMemory memory = new ChapterMemory();
        memory.setChapterId(chapterId);
        memory.setSummary("主角初入宗门");
        chapterMemoryMapper.insert(memory);

        ChapterVersion version = new ChapterVersion();
        version.setChapterId(chapterId);
        version.setVersionNo(1);
        version.setContent("正文快照");
        version.setSource("MANUAL");
        chapterVersionMapper.insert(version);

        projectService.delete(projectId);

        assertThrows(BusinessException.class, () -> projectService.requireById(projectId));
        assertEquals(0, characterMapper.selectByProjectId(projectId).size());
        assertEquals(0, settingMapper.selectByProjectId(projectId).size());
        assertEquals(0, chapterMapper.selectByProjectId(projectId).size());
        assertNull(chapterMemoryMapper.selectByChapterId(chapterId));
        assertEquals(0, chapterVersionMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ChapterVersion>()
                        .eq(ChapterVersion::getChapterId, chapterId)));
    }
}
