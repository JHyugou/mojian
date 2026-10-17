package com.mojian.agent.service;

import com.mojian.agent.llm.AgentMessage;
import com.mojian.agent.llm.AgentToolCall;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 写作会话的长期存续。
 *
 * <p>它守的是"副 Agent 有记忆"这条需求 —— 每章新开一段对话，
 * 它就永远是个只有当前这一章记忆的写手，用户说过的偏好下一章就忘。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WriterConversationTest {

    @Autowired
    private ConversationService conversationService;
    @Autowired
    private ProjectService projectService;

    private Long newProject(String name) {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName(name);
        return projectService.create(request).getId();
    }

    @Test
    @DisplayName("⭐ 整本书共用一个写作会话：跨章存续，不是每章一段")
    void 写作会话跨章存续() {
        Long projectId = newProject("写作会话测试");

        AgentConversation first = conversationService.findOrCreateWriter(projectId);
        AgentConversation second = conversationService.findOrCreateWriter(projectId);

        assertThat(second.getId())
                .as("同一本书取两次应当是同一段会话")
                .isEqualTo(first.getId());
    }

    @Test
    @DisplayName("不同的书各用各的会话，不会串台")
    void 不同书不共用会话() {
        Long bookA = newProject("甲书");
        Long bookB = newProject("乙书");

        assertThat(conversationService.findOrCreateWriter(bookB).getId())
                .isNotEqualTo(conversationService.findOrCreateWriter(bookA).getId());
    }

    @Test
    @DisplayName("⭐ 长期历史成对取舍：工具消息与带 tool_calls 的回答都不进历史")
    void 历史成对取舍() {
        Long projectId = newProject("历史测试");
        Long conversationId = conversationService.findOrCreateWriter(projectId).getId();

        conversationService.append(conversationId, 1, AgentMessage.user("把开头改陡一点"));
        // 中间那一轮：模型只要求调工具，没有正文
        conversationService.append(conversationId, 2, AgentMessage.assistant(null,
                List.of(new AgentToolCall("c1", "query_outline", "{}"))));
        // 工具结果又长又是临时的，不该留在长期历史里
        conversationService.append(conversationId, 2, AgentMessage.tool("c1", "■ [节点ID 1] 第一章"));
        conversationService.append(conversationId, 3, AgentMessage.assistant("改好了，只动了开头。", null));

        List<AgentMessage> history = conversationService.loadTextHistory(conversationId);

        assertThat(history).as("只留两条人话").hasSize(2);
        assertThat(history.get(0).role()).isEqualTo(AgentMessage.ROLE_USER);
        assertThat(history.get(0).content()).isEqualTo("把开头改陡一点");
        assertThat(history.get(1).content()).isEqualTo("改好了，只动了开头。");

        // 关键：带 tool_calls 的消息必须一起被剔除。
        // 协议要求 tool_calls 后面紧跟 tool 结果，只留一半会被服务方直接拒绝。
        assertThat(history).noneMatch(m -> AgentMessage.ROLE_TOOL.equals(m.role()));
        assertThat(history).noneMatch(m -> m.toolCalls() != null && !m.toolCalls().isEmpty());
        assertThat(history).allMatch(m -> m.content() != null && !m.content().isBlank());
    }
}
