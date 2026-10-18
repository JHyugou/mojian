package com.mojian.agent;

import com.mojian.agent.draft.DraftStatus;
import com.mojian.agent.draft.DraftType;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.entity.AgentDraft;
import com.mojian.agent.service.AgentUndoLogService;
import com.mojian.agent.service.ConversationRollbackService;
import com.mojian.agent.service.ConversationService;
import com.mojian.agent.service.DraftService;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import com.mojian.world.setting.dto.SettingSaveRequest;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对话回滚 → 数据库撤销的集成测试。
 *
 * <p>守的是一条很容易只做一半的行为：对话消息删了，书里的改动也要能按回合撤掉；
 * 且撤销不是毁尸灭迹 —— 被撤销的草稿要退回待确认，用户还能重新决定。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentUndoLogTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private DraftService draftService;
    @Autowired
    private ConversationRollbackService rollbackService;
    @Autowired
    private CharacterService characterService;
    @Autowired
    private SettingService settingService;
    @Autowired
    private AgentUndoLogService undoLogService;

    private Long createProject() {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName("撤销测试项目");
        return projectService.create(request).getId();
    }

    private AgentDraft draftCharacter(AgentConversation conversation, Long projectId) {
        AgentDraft draft = draftService.draft(conversation.getId(), projectId, DraftType.CHARACTER,
                "龙骨守门人", Map.of("name", "龙骨守门人", "roleIdentity", "守门",
                        "description", "守在龙骨祭坛旁"), "测试人物");
        return draftService.accept(draft.getId());
    }

    @Test
    @DisplayName("回滚对话：撤销之后的修改，保留之前的改动，草稿退回待确认")
    void 回滚撤销数据库改动并退回草稿() {
        Long projectId = createProject();
        AgentConversation conversation = conversationService.create(projectId, "PLANNER", "先建一个人物");
        conversationService.append(conversation.getId(), 0,
                com.mojian.agent.llm.AgentMessage.user("先建一个人物"));
        AgentDraft characterDraft = draftCharacter(conversation, projectId);

        CharacterProfile created = characterService.requireById(characterDraft.getAppliedId());
        assertThat(created.getName()).isEqualTo("龙骨守门人");

        // 第二个回合：修改已有设定（先手工建一条设定）
        conversationService.append(conversation.getId(), 0,
                com.mojian.agent.llm.AgentMessage.user("把设定改一下"));
        SettingSaveRequest settingRequest = new SettingSaveRequest();
        settingRequest.setTitle("OLD_SETTING");
        settingRequest.setContent("OLD_CONTENT");
        settingRequest.setPriority(0);
        settingRequest.setEnabled(true);
        SettingEntry setting = settingService.create(projectId, settingRequest);

        AgentDraft updateDraft = draftService.draft(conversation.getId(), projectId, DraftType.SETTING,
                "改设定", Map.of("title", "NEW_SETTING", "content", "NEW_CONTENT"), "修改设定",
                setting.getId());
        draftService.accept(updateDraft.getId());
        assertThat(settingService.requireById(setting.getId()).getTitle()).isEqualTo("NEW_SETTING");

        // 回滚到第一个回合：第二回合的修改撤销，第一回合的人物保留
        Map<String, Object> result = rollbackService.rollback(conversation.getId(), 1, true);
        assertThat(result.get("undone")).isEqualTo(1);
        assertThat(settingService.requireById(setting.getId()).getTitle()).isEqualTo("OLD_SETTING");
        assertThat(characterService.requireById(created.getId()).getName()).isEqualTo("龙骨守门人");
        assertThat(draftService.require(updateDraft.getId()).getStatus()).isEqualTo(DraftStatus.PENDING);

        // 再回滚到零：第一回合的人物也撤销
        rollbackService.rollback(conversation.getId(), 0, true);
        assertThat(characterService.getById(created.getId())).isNull();
        assertThat(draftService.require(characterDraft.getId()).getStatus()).isEqualTo(DraftStatus.PENDING);
    }

    @Test
    @DisplayName("关闭数据库回滚时只删消息，不动业务数据")
    void 只回滚对话() {
        Long projectId = createProject();
        AgentConversation conversation = conversationService.create(projectId, "PLANNER", "建一个人物");
        conversationService.append(conversation.getId(), 0,
                com.mojian.agent.llm.AgentMessage.user("建一个人物"));
        AgentDraft characterDraft = draftCharacter(conversation, projectId);
        CharacterProfile created = characterService.requireById(characterDraft.getAppliedId());

        rollbackService.rollback(conversation.getId(), 0, false);

        assertThat(characterService.getById(created.getId())).isNotNull();
        assertThat(undoLogService.undoAfter(conversation.getId(), 0)).isEqualTo(1);
    }
}
