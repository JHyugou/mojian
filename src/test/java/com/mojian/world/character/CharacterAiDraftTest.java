package com.mojian.world.character;

import com.mojian.common.exception.BusinessException;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.service.CharacterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FR-018 对话示例 AI 草稿：同步轻量调用、失败不影响档案。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CharacterAiDraftTest {

    @Autowired
    private CharacterService characterService;
    @Autowired
    private ProjectService projectService;

    @Test
    void draftReturnsDialogueText() {
        ProjectSaveRequest projectReq = new ProjectSaveRequest();
        projectReq.setName("AI草稿作品");
        Long projectId = projectService.create(projectReq).getId();

        CharacterSaveRequest req = new CharacterSaveRequest();
        req.setName("林晚照");
        req.setPersonality("外冷内热");
        Long characterId = characterService.create(projectId, req).getId();

        String draft = characterService.draftDialogueExample(characterId, "口头禅是'无所谓'");

        assertNotNull(draft);
        assertTrue(draft.contains("："), "应输出对话格式");
        assertTrue(draft.contains("林晚照"), "对话应嵌入人物姓名（Mock 验证上下文生效）");
    }

    @Test
    void draftMissingCharacterFails() {
        assertThrows(BusinessException.class,
                () -> characterService.draftDialogueExample(-1L, null));
    }
}
