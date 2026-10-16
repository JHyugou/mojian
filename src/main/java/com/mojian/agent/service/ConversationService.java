package com.mojian.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.entity.AgentMessageLog;
import com.mojian.agent.entity.AgentPlanOption;
import com.mojian.agent.llm.AgentMessage;
import com.mojian.agent.llm.AgentToolCall;
import com.mojian.agent.mapper.AgentConversationMapper;
import com.mojian.agent.mapper.AgentMessageLogMapper;
import com.mojian.agent.mapper.AgentPlanOptionMapper;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话与消息的持久化。
 *
 * <p>它负责一件事：<b>让那个"一轮轮长大的包裹"能跨请求、跨重启地活着</b>。
 * 写正文可能持续几分钟，服务还可能重启；用户也要能翻回历史对话看 Agent 当时查了什么。
 *
 * <p>注意它不在协议层做任何加工 —— 存进去什么、读出来就是什么，
 * 组装成"发给模型的包裹"是 {@code AgentLoopService} 的事。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TITLE_MAX_LENGTH = 40;

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageLogMapper messageLogMapper;
    private final AgentPlanOptionMapper planOptionMapper;

    // ==================== 会话 ====================

    public AgentConversation create(Long projectId, String agentRole, String firstUserMessage) {
        AgentConversation conversation = new AgentConversation();
        conversation.setProjectId(projectId);
        conversation.setAgentRole(agentRole);
        conversation.setTitle(deriveTitle(firstUserMessage));
        conversationMapper.insert(conversation);
        log.debug("新建会话 {}（项目 {} / {}）", conversation.getId(), projectId, agentRole);
        return conversation;
    }

    public AgentConversation require(Long conversationId) {
        AgentConversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "会话不存在: " + conversationId);
        }
        return conversation;
    }

    public List<AgentConversation> list(Long projectId, String agentRole) {
        return conversationMapper.selectByProject(projectId, agentRole);
    }

    private String deriveTitle(String firstUserMessage) {
        if (!StringUtils.hasText(firstUserMessage)) {
            return "新会话";
        }
        String trimmed = firstUserMessage.strip().replaceAll("\\s+", " ");
        return trimmed.length() <= TITLE_MAX_LENGTH ? trimmed : trimmed.substring(0, TITLE_MAX_LENGTH) + "…";
    }

    // ==================== 消息 ====================

    /**
     * 追加一条消息。
     *
     * <p>每追加一条就落库（而不是攒一批再写）：Agent 跑到一半失败或服务重启时，
     * 已经发生的工具调用不会丢 —— 排查问题时最想看的恰恰是"崩之前它查了什么"。
     */
    @Transactional
    public AgentMessageLog append(Long conversationId, int stepNo, AgentMessage message) {
        AgentMessageLog logRecord = new AgentMessageLog();
        logRecord.setConversationId(conversationId);
        logRecord.setStepNo(stepNo);
        logRecord.setRole(message.role());
        logRecord.setContent(message.content());
        logRecord.setToolCallId(message.toolCallId());
        logRecord.setToolCallsJson(writeToolCalls(message.toolCalls()));
        messageLogMapper.insert(logRecord);
        return logRecord;
    }

    @Transactional
    public void appendAll(Long conversationId, int stepNo, List<AgentMessage> messages) {
        for (AgentMessage message : messages) {
            append(conversationId, stepNo, message);
        }
    }

    /** 按落库顺序读回整段会话 —— 这个顺序就是"包裹"的组装顺序，不能乱 */
    public List<AgentMessage> loadMessages(Long conversationId) {
        List<AgentMessage> messages = new ArrayList<>();
        for (AgentMessageLog record : messageLogMapper.selectByConversation(conversationId)) {
            messages.add(new AgentMessage(record.getRole(), record.getContent(), record.getToolCallId(),
                    readToolCalls(record.getToolCallsJson())));
        }
        return messages;
    }

    /** 原始记录（带 stepNo 等字段），给界面展示 trace 用 */
    public List<AgentMessageLog> loadLogs(Long conversationId) {
        return messageLogMapper.selectByConversation(conversationId);
    }

    // ==================== 剧情方案 ====================

    /** 主 Agent 给出的走向方案 */
    public record PlanOptionDraft(String title, String summary, String detail) {
    }

    @Transactional
    public List<AgentPlanOption> savePlanOptions(Long conversationId, Long messageId,
                                                 List<PlanOptionDraft> drafts) {
        List<AgentPlanOption> saved = new ArrayList<>();
        int index = 1;
        for (PlanOptionDraft draft : drafts) {
            AgentPlanOption option = new AgentPlanOption();
            option.setConversationId(conversationId);
            option.setMessageId(messageId);
            option.setOptionIndex(index++);
            option.setTitle(draft.title());
            option.setSummary(draft.summary());
            option.setDetail(draft.detail());
            option.setSelected(false);
            planOptionMapper.insert(option);
            saved.add(option);
        }
        return saved;
    }

    /** 用户选定某一条走向：同一批里只能有一条被选中 */
    @Transactional
    public AgentPlanOption selectOption(Long conversationId, int optionIndex) {
        List<AgentPlanOption> options = planOptionMapper.selectByConversation(conversationId);
        AgentPlanOption chosen = options.stream()
                .filter(o -> o.getOptionIndex() != null && o.getOptionIndex() == optionIndex)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "方案不存在: " + optionIndex));
        for (AgentPlanOption option : options) {
            boolean shouldSelect = option.getId().equals(chosen.getId());
            if (!Boolean.valueOf(shouldSelect).equals(option.getSelected())) {
                option.setSelected(shouldSelect);
                planOptionMapper.updateById(option);
            }
        }
        return chosen;
    }

    public List<AgentPlanOption> listPlanOptions(Long conversationId) {
        return planOptionMapper.selectByConversation(conversationId);
    }

    // ==================== 协议字段的编解码 ====================

    /**
     * 工具调用存成 JSON 数组。
     *
     * <p>为什么不用"一个工具一列"：一轮里模型可能同时要求调 3 个工具，
     * 那在协议上是<b>一条 assistant 消息 + 3 条 tool 消息</b>，
     * 按"一消息一工具"存会丢掉"这三个调用属于同一轮"这个结构。
     */
    private String writeToolCalls(List<AgentToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        ArrayNode array = MAPPER.createArrayNode();
        for (AgentToolCall call : calls) {
            ObjectNode node = array.addObject();
            node.put("id", call.id());
            node.put("name", call.name());
            node.put("arguments", call.arguments());
        }
        return array.toString();
    }

    private List<AgentToolCall> readToolCalls(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            JsonNode array = MAPPER.readTree(json);
            List<AgentToolCall> calls = new ArrayList<>();
            for (JsonNode node : array) {
                calls.add(new AgentToolCall(
                        node.path("id").asText(""),
                        node.path("name").asText(""),
                        node.path("arguments").asText("{}")));
            }
            return calls;
        } catch (Exception e) {
            // 解不出来不该让整段会话报废：当成"这一轮没有工具调用"继续读，
            // 否则一条坏数据会让用户永远打不开这个会话
            log.warn("工具调用 JSON 解析失败，按空处理: {}", e.getMessage());
            return List.of();
        }
    }
}
