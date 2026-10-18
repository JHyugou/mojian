package com.mojian.agent;

import com.mojian.agent.entity.AgentDraft;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.service.ConversationService;
import com.mojian.agent.service.DraftService;
import com.mojian.agent.tool.impl.UpdateCharacterTool;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.world.character.dto.CharacterSaveRequest;
import com.mojian.world.character.entity.CharacterProfile;
import com.mojian.world.character.service.CharacterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 策划修改人物卡：改名必须落到记录 name（也就是人物卡标题），不能只改正文里的称呼。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UpdateCharacterToolTest {

    @Autowired
    private UpdateCharacterTool tool;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private CharacterService characterService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private DraftService draftService;

    private CharacterProfile character(Long projectId, String name) {
        CharacterSaveRequest request = new CharacterSaveRequest();
        request.setName(name);
        request.setRoleIdentity("旧身份");
        request.setDescription("旧设定");
        return characterService.create(projectId, request);
    }

    @Test
    @DisplayName("只传 name 也能改名，确认后记录标题同步更新")
    void 改名落到记录标题() {
        ProjectSaveRequest projectRequest = new ProjectSaveRequest();
        projectRequest.setName("改名测试");
        Long projectId = projectService.create(projectRequest).getId();
        CharacterProfile profile = character(projectId, "芜");
        AgentConversation conversation = conversationService.create(projectId, AgentRole.PLANNER, "改名字");

        String result = tool.execute(
                AgentContext.ofConversation(projectId, AgentRole.PLANNER, conversation.getId()),
                Map.of("characterId", profile.getId(), "name", "陌", "reason", "用户要求把名字改成陌"));

        assertThat(result).contains("改名为「陌」").contains("尚未生效");

        AgentDraft draft = draftService.listPendingByProject(projectId).stream()
                .filter(item -> profile.getId().equals(item.getTargetId()))
                .findFirst().orElseThrow();
        assertThat(draft.getTitle()).isEqualTo("陌");
        assertThat(draft.getTargetId()).isEqualTo(profile.getId());

        draftService.accept(draft.getId());

        CharacterProfile renamed = characterService.requireById(profile.getId());
        assertThat(renamed.getName()).isEqualTo("陌");
        assertThat(renamed.getRoleIdentity()).as("没传的字段保持原样").isEqualTo("旧身份");
        assertThat(renamed.getDescription()).as("没传的字段保持原样").isEqualTo("旧设定");
    }

    @Test
    @DisplayName("三个可改字段全空时拒绝；非法字符长度拒绝")
    void 空修改拒绝() {
        ProjectSaveRequest projectRequest = new ProjectSaveRequest();
        projectRequest.setName("空改测试");
        Long projectId = projectService.create(projectRequest).getId();
        CharacterProfile profile = character(projectId, "甲");
        AgentConversation conversation = conversationService.create(projectId, AgentRole.PLANNER, "空改");

        String empty = tool.execute(
                AgentContext.ofConversation(projectId, AgentRole.PLANNER, conversation.getId()),
                Map.of("characterId", profile.getId(), "reason", "测试"));
        assertThat(empty).contains("至少要提供一个");

        String tooLong = tool.execute(
                AgentContext.ofConversation(projectId, AgentRole.PLANNER, conversation.getId()),
                Map.of("characterId", profile.getId(), "name", "名".repeat(51), "reason", "测试"));
        assertThat(tooLong).contains("不能超过 50 字");
    }
}
