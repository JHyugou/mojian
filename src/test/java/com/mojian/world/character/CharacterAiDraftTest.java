package com.mojian.world.character;

import com.mojian.common.exception.BusinessException;
import com.mojian.generation.llm.LlmClient;
import com.mojian.generation.llm.RoutingLlmClient;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.dto.SettingSaveRequest;
import com.mojian.world.setting.service.SettingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FR-018 对话示例 AI 草稿：同步轻量调用、失败不影响档案；
 * 草稿提示词必须注入与人物相关的世界观设定（FR-018 增强）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CharacterAiDraftTest {

    @Autowired
    private CharacterService characterService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private SettingService settingService;
    @Autowired
    private LlmClient llmClient;

    @Test
    void draftReturnsDialogueText() {
        ProjectSaveRequest projectReq = new ProjectSaveRequest();
        projectReq.setName("AI草稿作品");
        Long projectId = projectService.create(projectReq).getId();

        CharacterSaveRequest req = new CharacterSaveRequest();
        req.setName("林晚照");
        req.setDescription("外冷内热，言语简省，爱用反问");
        Long characterId = characterService.create(projectId, req).getId();

        String draft = characterService.draftDialogueExample(characterId, "口头禅是'无所谓'");

        assertNotNull(draft);
        assertTrue(draft.contains("："), "应输出对话格式");
        assertTrue(draft.contains("林晚照"), "对话应嵌入人物姓名（Mock 验证上下文生效）");
    }

    @Test
    void draftPromptCarriesRelatedWorldSettings() {
        ProjectSaveRequest projectReq = new ProjectSaveRequest();
        projectReq.setName("AI草稿作品·设定");
        Long projectId = projectService.create(projectReq).getId();

        SettingSaveRequest settingReq = new SettingSaveRequest();
        settingReq.setTitle("北境剑冢");
        settingReq.setContent("万剑埋骨之地，剑修以剑起誓");
        settingReq.setTriggerKeywords("剑冢,雪原");
        settingService.create(projectId, settingReq);

        CharacterSaveRequest req = new CharacterSaveRequest();
        req.setName("琳瑶");
        req.setDescription("镇守剑冢的守剑人，与顾行舟有旧约");
        Long characterId = characterService.create(projectId, req).getId();

        CharacterSaveRequest otherReq = new CharacterSaveRequest();
        otherReq.setName("顾行舟");
        otherReq.setRoleIdentity("游历的雷法修士");
        otherReq.setDescription("琳瑶的旧识");
        characterService.create(projectId, otherReq).getId();

        characterService.draftDialogueExample(characterId, null);

        String prompt = ((RoutingLlmClient) llmClient).getLastUserPrompt();
        assertNotNull(prompt);
        assertTrue(prompt.contains("【世界观设定】"), "草稿提示词应包含世界观设定段");
        assertTrue(prompt.contains("北境剑冢"), "与人物语料相关的设定应被注入");
        assertTrue(prompt.contains("【其他人物】"), "草稿提示词应包含其他人物段");
        assertTrue(prompt.contains("顾行舟"), "其他人物应注入（关系人优先）");
        assertTrue(prompt.contains("游历的雷法修士"), "其他人物的摘要应被注入");
        assertFalse(prompt.contains("【其他人物】【"), "无重复段");
    }

    @Test
    void draftMissingCharacterFails() {
        assertThrows(BusinessException.class,
                () -> characterService.draftDialogueExample(-1L, null));
    }
}
