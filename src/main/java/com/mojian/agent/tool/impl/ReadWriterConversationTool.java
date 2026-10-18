package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.entity.AgentMessageLog;
import com.mojian.agent.entity.WriterInstruction;
import com.mojian.agent.service.ConversationService;
import com.mojian.agent.service.WriterInstructionService;
import com.mojian.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 策划查看作者与写作 Agent 的对话。
 *
 * <p>写作 Agent 经常在回答里提出问题、说明取舍、给出建议；策划审稿时需要看到这些，
 * 才能判断哪些地方是"写作 Agent 已经说明过的"，哪些需要补要求。
 * 这个工具只读，不创建会话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadWriterConversationTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;
    private static final int MAX_MESSAGE_CHARS = 2500;

    private final ConversationService conversationService;
    private final WriterInstructionService instructionService;

    @Override
    public String name() {
        return "read_writer_conversation";
    }

    @Override
    public String description() {
        return "查看作者与写作 Agent 的整本书写作会话（只读）。"
                + "写作 Agent 会在回答里说明改动、提出问题或给出建议；审稿前需要了解这些上下文时用它。"
                + "可选 limit 控制最近消息条数（默认 20，最多 50）。"
                + "注意：对话只是过程记录，判断正文质量仍要以 review_chapter 看的正文为准。";
    }

    @Override
    public List<String> roles() {
        return List.of("PLANNER");
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "limit", Map.of("type", "integer",
                                "description", "查看最近多少条消息（默认 20，最多 50）")),
                "required", List.of());
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        int limit = intOf(arguments.get("limit"), DEFAULT_LIMIT);
        limit = Math.max(1, Math.min(limit, MAX_LIMIT));

        Optional<AgentConversation> conversation = conversationService.findWriter(projectId);
        StringBuilder sb = new StringBuilder();
        if (conversation.isEmpty()) {
            sb.append("本书还没有写作会话；写作 Agent 还没有和作者说过话。\n");
        } else {
            List<AgentMessageLog> logs = conversationService.loadTextLogs(conversation.get().getId());
            List<AgentMessageLog> recent = logs.size() <= limit
                    ? logs : logs.subList(logs.size() - limit, logs.size());
            sb.append("【作者与写作 Agent 的对话】（整本书一条会话，以下为最近 ")
                    .append(recent.size()).append(" 条）\n");
            if (recent.isEmpty()) {
                sb.append("（暂无文本消息）\n");
            }
            for (AgentMessageLog message : recent) {
                String who = "user".equals(message.getRole()) ? "作者" : "写作 Agent";
                sb.append("\n[").append(who).append("] ");
                if (message.getCreatedAt() != null) {
                    sb.append("(").append(message.getCreatedAt().toString().replace('T', ' ')).append(") ");
                }
                String content = message.getContent() == null ? "" : message.getContent().trim();
                if (content.length() > MAX_MESSAGE_CHARS) {
                    content = content.substring(0, MAX_MESSAGE_CHARS) + "……（本条过长，已截断）";
                }
                sb.append(content).append('\n');
            }
        }

        List<WriterInstruction> instructions = instructionService.recent(projectId, 10);
        if (!instructions.isEmpty()) {
            sb.append("\n【策划此前下发的写作指令】（供参考，不代表已经执行）\n");
            for (WriterInstruction instruction : instructions) {
                sb.append("- [").append("PENDING".equals(instruction.getStatus()) ? "待执行" : "已执行")
                        .append("] ").append(instruction.getContent()).append('\n');
            }
        }
        sb.append("\n请结合 review_chapter 看到的当前正文判断；"
                + "写作 Agent 的自我总结不能代替你对正文的审阅，对话里的旧要求也以当前正文/写作要求为准。");
        return sb.toString();
    }

    private int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
