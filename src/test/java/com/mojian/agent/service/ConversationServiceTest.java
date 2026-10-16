package com.mojian.agent.service;

import com.mojian.agent.AgentRole;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.entity.AgentPlanOption;
import com.mojian.agent.llm.AgentMessage;
import com.mojian.agent.llm.AgentToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话持久化测试（真库，事务回滚）。
 *
 * <p>重点验证一件事：<b>存进去的包裹，读出来必须一模一样</b>。
 * 这是整个 Agent 记忆的地基 —— 少了工具调用、串了顺序，模型行为就会变得莫名其妙，
 * 而且这种错误不会报异常，只会表现为"模型好像忘了刚才查过什么"。
 */
@SpringBootTest
@Transactional
class ConversationServiceTest {

    private static final Long PROJECT_ID = 999_000_001L;

    @Autowired
    private ConversationService conversationService;

    @Test
    @DisplayName("消息往返保真：system/user/assistant/tool 四类都能原样读回")
    void 消息往返保真() {
        AgentConversation conversation = conversationService.create(PROJECT_ID, AgentRole.PLANNER, "给主角加一个师父");

        conversationService.append(conversation.getId(), 1, AgentMessage.system("你是墨笺助手"));
        conversationService.append(conversation.getId(), 1, AgentMessage.user("给主角加一个师父"));
        conversationService.append(conversation.getId(), 1,
                AgentMessage.assistant(null, List.of(new AgentToolCall("call_1", "query_character", "{\"name\":\"寒舟\"}"))));
        conversationService.append(conversation.getId(), 1, AgentMessage.tool("call_1", "寒舟：32 岁，道盟成员"));

        List<AgentMessage> loaded = conversationService.loadMessages(conversation.getId());

        assertThat(loaded).hasSize(4);
        assertThat(loaded.get(0).role()).isEqualTo("system");
        assertThat(loaded.get(0).content()).isEqualTo("你是墨笺助手");
        assertThat(loaded.get(3).role()).isEqualTo("tool");
        assertThat(loaded.get(3).toolCallId()).isEqualTo("call_1");
        assertThat(loaded.get(3).content()).isEqualTo("寒舟：32 岁，道盟成员");
    }

    @Test
    @DisplayName("一轮里三个工具调用的结构不丢：仍是一条 assistant + 三条 tool")
    void 一轮多个工具调用结构不丢() {
        AgentConversation conversation = conversationService.create(PROJECT_ID, AgentRole.PLANNER, "写第三章");

        // 这是关键场景：模型一轮里同时要求调三个工具
        conversationService.append(conversation.getId(), 1, AgentMessage.assistant(null, List.of(
                new AgentToolCall("c1", "query_character", "{\"name\":\"寒舟\"}"),
                new AgentToolCall("c2", "query_setting", "{\"keyword\":\"道盟\"}"),
                new AgentToolCall("c3", "query_chapter_memory", "{}"))));
        conversationService.append(conversation.getId(), 1, AgentMessage.tool("c1", "人物资料"));
        conversationService.append(conversation.getId(), 1, AgentMessage.tool("c2", "设定资料"));
        conversationService.append(conversation.getId(), 1, AgentMessage.tool("c3", "前情摘要"));

        List<AgentMessage> loaded = conversationService.loadMessages(conversation.getId());

        assertThat(loaded).hasSize(4);
        List<AgentToolCall> calls = loaded.get(0).toolCalls();
        assertThat(calls).extracting(AgentToolCall::name)
                .containsExactly("query_character", "query_setting", "query_chapter_memory");
        assertThat(calls.get(1).arguments()).contains("道盟");
        // assistant 行自己不带 toolCallId，它带的是 toolCalls 数组；toolCallId 是 tool 行的事
        assertThat(loaded.get(0).toolCallId()).isNull();
        assertThat(loaded.get(0).toolCalls()).hasSize(3);
        assertThat(loaded.get(1).toolCallId()).isEqualTo("c1");
    }

    @Test
    @DisplayName("消息顺序就是包裹顺序：按落库顺序读回，不重排")
    void 消息顺序稳定() {
        AgentConversation conversation = conversationService.create(PROJECT_ID, AgentRole.WRITER, "写第一章");
        for (int i = 1; i <= 10; i++) {
            conversationService.append(conversation.getId(), i, AgentMessage.user("第 " + i + " 句"));
        }

        List<AgentMessage> loaded = conversationService.loadMessages(conversation.getId());

        assertThat(loaded).extracting(AgentMessage::content)
                .containsExactly("第 1 句", "第 2 句", "第 3 句", "第 4 句", "第 5 句",
                        "第 6 句", "第 7 句", "第 8 句", "第 9 句", "第 10 句");
    }

    @Test
    @DisplayName("会话标题取首条消息前 40 字，超长截断")
    void 会话标题截断() {
        String longMessage = "字".repeat(100);

        AgentConversation conversation = conversationService.create(PROJECT_ID, AgentRole.PLANNER, longMessage);

        assertThat(conversation.getTitle()).hasSize(41).endsWith("…");
    }

    @Test
    @DisplayName("剧情方案：保存后能按序读回，选定一条时其余自动取消选中")
    void 剧情方案选定互斥() {
        AgentConversation conversation = conversationService.create(PROJECT_ID, AgentRole.PLANNER, "主角遇到剑客");

        conversationService.savePlanOptions(conversation.getId(), null, List.of(
                new ConversationService.PlanOptionDraft("旧识重逢", "剑客是失散多年的旧友", "详细走向 A"),
                new ConversationService.PlanOptionDraft("仇敌相见", "剑客是来寻仇的", "详细走向 B"),
                new ConversationService.PlanOptionDraft("卧底身份", "剑客奉命接近主角", "详细走向 C")));

        List<AgentPlanOption> saved = conversationService.listPlanOptions(conversation.getId());
        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(AgentPlanOption::getTitle)
                .containsExactly("旧识重逢", "仇敌相见", "卧底身份");
        assertThat(saved).allMatch(o -> Boolean.FALSE.equals(o.getSelected()));

        conversationService.selectOption(conversation.getId(), 2);

        List<AgentPlanOption> afterSelect = conversationService.listPlanOptions(conversation.getId());
        assertThat(afterSelect).filteredOn(AgentPlanOption::getSelected)
                .singleElement()
                .extracting(AgentPlanOption::getTitle)
                .isEqualTo("仇敌相见");
    }
}
