package com.mojian.agent;

import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.llm.AgentMessage;
import com.mojian.agent.service.ConversationService;
import com.mojian.agent.service.WriterInstructionService;
import com.mojian.agent.tool.impl.ReadWriterConversationTool;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 策划查看写作会话：能看到作者与写作 Agent 的消息、最近条数限制、以及策划指令状态。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ReadWriterConversationToolTest {

    @Autowired
    private ReadWriterConversationTool tool;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private WriterInstructionService instructionService;

    private Long project() {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName("写作会话读取测试");
        return projectService.create(request).getId();
    }

    private void append(AgentConversation conversation, String role, String content) {
        conversationService.append(conversation.getId(), 0,
                "user".equals(role) ? AgentMessage.user(content) : AgentMessage.assistant(content, null));
    }

    @Test
    @DisplayName("策划能看到作者与写作 Agent 的对话和写作 Agent 的建议")
    void 读取写作会话() {
        Long projectId = project();
        AgentConversation conversation = conversationService.findOrCreateWriter(projectId);
        append(conversation, "user", "这一章结尾写主角的生死，要明确。");
        append(conversation, "assistant",
                "两处拿不准：第一，甲叶子响的位置我放在报完名字之后；第二，全章没让嬴块看清嬴珩的脸。");
        instructionService.create(projectId, 1L, "结尾改成动作收束，不要解释生死。", "审稿意见");

        String result = tool.execute(AgentContext.of(projectId, AgentRole.PLANNER), Map.of());

        assertThat(result).contains("作者与写作 Agent 的对话")
                .contains("作者").contains("写作 Agent")
                .contains("甲叶子响的位置")
                .contains("结尾改成动作收束")
                .contains("不代表已经执行");
    }

    @Test
    @DisplayName("limit 只取最近 N 条；没有写作会话时不创建空会话")
    void 限制条数与无会话() {
        Long projectId = project();
        AgentConversation conversation = conversationService.findOrCreateWriter(projectId);
        for (int i = 1; i <= 5; i++) {
            append(conversation, i % 2 == 1 ? "user" : "assistant", "消息" + i);
        }

        String recent = tool.execute(AgentContext.of(projectId, AgentRole.PLANNER), Map.of("limit", 2));
        assertThat(recent).contains("消息4").contains("消息5").doesNotContain("消息1");

        Long emptyProject = project();
        String empty = tool.execute(AgentContext.of(emptyProject, AgentRole.PLANNER), Map.of());
        assertThat(empty).contains("还没有写作会话");
        assertThat(conversationService.findWriter(emptyProject)).isEmpty();
    }
}
