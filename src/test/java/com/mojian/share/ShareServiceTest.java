package com.mojian.share;

import com.mojian.chapter.dto.ChapterMemorySaveRequest;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.mapper.ChapterMapper;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.chapter.service.ChapterService;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.outline.service.OutlineService;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.dto.CharacterNodeSaveRequest;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.mapper.CharacterNodeMapper;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.mapper.SettingEntryMapper;
import com.mojian.world.setting.service.SettingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分享中心集成测试（FR-022）：项目全量导出→导入往返一致性、format 校验、缺必填拒绝。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ShareServiceTest {

    @Autowired
    private ShareService shareService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CharacterService characterService;
    @Autowired
    private CharacterNodeMapper characterNodeMapper;
    @Autowired
    private SettingEntryMapper settingEntryMapper;
    @Autowired
    private SettingService settingService;
    @Autowired
    private ChapterService chapterService;
    @Autowired
    private ChapterMapper chapterMapper;
    @Autowired
    private ChapterMemoryMapper chapterMemoryMapper;
    @Autowired
    private OutlineService outlineService;

    /** 搭一个含人物（带演变节点）、设定、蓝图、章节（含记忆）的完整项目 */
    private Long buildSampleProject(String name) {
        ProjectSaveRequest projectReq = new ProjectSaveRequest();
        projectReq.setName(name);
        projectReq.setGenre("仙侠");
        projectReq.setIntro("一把旧剑的故事");
        Long projectId = projectService.create(projectReq).getId();

        CharacterSaveRequest charReq = new CharacterSaveRequest();
        charReq.setName("琳瑶");
        charReq.setRoleIdentity("守剑人");
        charReq.setDescription("外冷内热，镇守剑冢");
        charReq.setExampleDialogue("“等雪停。”");
        Long characterId = characterService.create(projectId, charReq).getId();
        CharacterNodeSaveRequest nodeReq = new CharacterNodeSaveRequest();
        nodeReq.setChapterFrom(11);
        nodeReq.setDescription("觉醒血脉：筑基期，能驭雷");
        characterService.createNode(characterId, nodeReq);

        com.mojian.world.setting.dto.SettingSaveRequest settingReq =
                new com.mojian.world.setting.dto.SettingSaveRequest();
        settingReq.setTitle("剑冢");
        settingReq.setContent("万剑埋骨之地");
        settingReq.setTriggerKeywords("剑冢");
        settingReq.setPriority(5);
        settingService.create(projectId, settingReq);

        com.mojian.outline.dto.OutlineNodeSaveRequest volumeReq =
                new com.mojian.outline.dto.OutlineNodeSaveRequest();
        volumeReq.setNodeType("VOLUME");
        volumeReq.setName("第一卷");
        Long volumeId = outlineService.create(projectId, volumeReq).getId();
        com.mojian.outline.dto.OutlineNodeSaveRequest chapterNodeReq =
                new com.mojian.outline.dto.OutlineNodeSaveRequest();
        chapterNodeReq.setNodeType("CHAPTER");
        chapterNodeReq.setName("第一章 夜雨");
        chapterNodeReq.setParentId(volumeId);
        Long chapterNodeId = outlineService.create(projectId, chapterNodeReq).getId();

        ChapterSaveRequest chReq = new ChapterSaveRequest();
        chReq.setTitle("第一章 夜雨");
        chReq.setContent("夜雨落了整宿。");
        chReq.setStatus("WRITING");
        chReq.setOutlineNodeId(chapterNodeId);
        Long chapterId = chapterService.create(projectId, chReq).getId();
        ChapterMemorySaveRequest memoryReq = new ChapterMemorySaveRequest();
        memoryReq.setMemory("【章节摘要】主角抵达客栈；【未解决冲突】刺杀者身份未明");
        chapterService.saveMemory(chapterId, memoryReq);
        return projectId;
    }

    @Test
    void projectRoundTripPreservesEverything() {
        Long originId = buildSampleProject("交换测试·源");
        Map<String, Object> payload = shareService.exportProject(originId);
        assertEquals("mojian-project/v1", payload.get("format"));

        Map<String, Object> result = shareService.importProject(new HashMap<>(payload));
        Long newProjectId = Long.valueOf(String.valueOf(result.get("projectId")));
        assertNotEquals(originId, newProjectId);

        // 人物往返（含演变节点）
        var profiles = characterService.list(newProjectId);
        assertEquals(1, profiles.size());
        var profile = profiles.get(0);
        assertEquals("琳瑶", profile.getName());
        assertTrue(profile.getDescription().contains("镇守剑冢"));
        var nodes = characterService.listNodes(profile.getId());
        assertEquals(1, nodes.size());
        assertEquals(11, nodes.get(0).getChapterFrom());
        assertEquals("觉醒血脉：筑基期，能驭雷", nodes.get(0).getDescription());

        // 设定往返
        var settings = settingEntryMapper.selectByProjectId(newProjectId);
        assertEquals(1, settings.size());
        assertEquals("剑冢", settings.get(0).getTitle());
        assertEquals(5, settings.get(0).getPriority());

        // 蓝图往返（树结构）
        var tree = outlineService.tree(newProjectId);
        assertEquals(1, tree.size());
        assertEquals("第一卷", tree.get(0).getName());
        assertEquals(1, tree.get(0).getChildren().size());
        assertEquals("第一章 夜雨", tree.get(0).getChildren().get(0).getName());

        // 章节与记忆往返（蓝图关联按映射回填）
        var chapters = chapterMapper.selectByProjectId(newProjectId);
        assertEquals(1, chapters.size());
        var chapter = chapters.get(0);
        assertEquals("第一章 夜雨", chapter.getTitle());
        assertEquals("夜雨落了整宿。", chapter.getContent());
        assertEquals("WRITING", chapter.getStatus());
        assertNotNull(chapter.getOutlineNodeId(), "蓝图关联应按映射回填");
        var memory = chapterMemoryMapper.selectByChapterId(chapter.getId());
        assertNotNull(memory);
        assertTrue(memory.getMemory().contains("刺杀者身份未明"));
    }

    @Test
    void formatMismatchRejected() {
        Long originId = buildSampleProject("格式测试·源");
        Map<String, Object> payload = shareService.exportProject(originId);
        payload.put("format", "other-tool/v9");

        BusinessException e = assertThrows(BusinessException.class,
                () -> shareService.importProject(new HashMap<>(payload)));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getErrorCode().getCode());
    }

    @Test
    void characterWithoutNameRejectedWholesale() {
        Long originId = buildSampleProject("缺名测试·源");
        Map<String, Object> payload = shareService.exportProject(originId);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) payload.get("characters");
        data.get(0).remove("name");

        // 全或无：一条非法整批拒绝
        assertThrows(BusinessException.class, () -> shareService.importProject(new HashMap<>(payload)));
    }
}
