package com.mojian.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mojian.agent.AgentRole;
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

    /** 用户消息的角色名：回滚时靠它数「第几条用户消息」 */
    private static final String USER_ROLE = "user";


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

    /** 全部项目下的会话，最近用过的在前 —— 左侧工作区树用 */
    public List<AgentConversation> listAll(String agentRole) {
        return conversationMapper.selectAll(agentRole);
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
    /**
     * 回滚：从第 N 条用户消息开始，把这条<b>以及它后面的一切</b>从上下文里抹掉。
     *
     * <h3>为什么按"第几条用户消息"定位，而不是按消息 id</h3>
     * 前端手上只有渲染出来的对话数组，数组下标和数据库里的行号不是一回事 ——
     * 中间夹着工具调用与工具结果，一条用户消息后面可能跟着七八行。
     * 但"第几条用户消息"这个口径两边都能算：前端数自己数组里的 user 条目，
     * 这里数数据库里的 role='user' 行，必然对得上。
     *
     * <h3>为什么不真的删除</h3>
     * 这里确实是把行删了。之所以敢删，是因为这个操作是<b>用户对着自己看得见的那条消息</b>
     * 主动点的，而且删之前的消息仍然完整保留。
     * （如果将来要做"可撤销的回滚"，那才需要软删除 + 版本表；
     *  现在这个场景不值得为它引入一套回收站。）
     *
     * @param keepUserMessages 保留前几条用户消息；从第 keepUserMessages+1 条开始删
     * @return 实际删掉的行数
     */
    @Transactional
    public int rollbackToUserMessage(Long conversationId, int keepUserMessages) {
        List<AgentMessageLog> all = messageLogMapper.selectList(
                Wrappers.<AgentMessageLog>lambdaQuery()
                        .eq(AgentMessageLog::getConversationId, conversationId)
                        .orderByAsc(AgentMessageLog::getId));

        int seen = 0;
        Long cutFromId = null;
        for (AgentMessageLog message : all) {
            if (USER_ROLE.equals(message.getRole())) {
                seen++;
                if (seen == keepUserMessages + 1) {
                    cutFromId = message.getId();
                    break;
                }
            }
        }
        if (cutFromId == null) {
            return 0;
        }

        int deleted = messageLogMapper.delete(Wrappers.<AgentMessageLog>lambdaQuery()
                .eq(AgentMessageLog::getConversationId, conversationId)
                .ge(AgentMessageLog::getId, cutFromId));
        log.info("会话 {} 回滚：保留 {} 条用户消息，删除 {} 行", conversationId, keepUserMessages, deleted);
        return deleted;
    }

    /** 当前会话里有多少条用户消息 —— 数据库撤销日志用它标记"改动发生在第几个回合之后" */
    public int countUserMessages(Long conversationId) {
        if (conversationId == null) {
            return 0;
        }
        Long count = messageLogMapper.selectCount(Wrappers.<AgentMessageLog>lambdaQuery()
                .eq(AgentMessageLog::getConversationId, conversationId)
                .eq(AgentMessageLog::getRole, USER_ROLE));
        return count == null ? 0 : count.intValue();
    }

    @Transactional
    public AgentMessageLog append(Long conversationId, int stepNo, AgentMessage message) {
        /*
         * 会话可能已经被删了 —— 用户在任务跑着的时候删掉了那段对话。
         * 这时不该再往里写：写进去的就是永远没人看得到的孤儿数据，
         * 而且下次读历史时它们还会混进来，让模型看到一段"没有开头的对话"。
         */
        if (conversationMapper.selectById(conversationId) == null) {
            log.debug("会话 {} 已不存在，跳过写入（角色 {}）", conversationId, message.role());
            return null;
        }

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
                    readToolCalls(record.getToolCallsJson()), List.of()));
        }
        return messages;
    }

    /**
     * 只保留最近的、总字符数不超过预算的历史。
     *
     * <p>为什么必须要有上限：会话是<b>跨章长期存续</b>的，作者聊上几十轮之后，
     * 历史本身就会把上下文吃掉；而历史越多，真正重要的材料反而被挤压。
     * 从最新往回取，保证"最近的对话最完整"；早期内容丢掉的代价是可接受的
     * （真正的长期记忆已经落在章节记忆、人物卡与蓝图里）。
     */
    public static List<AgentMessage> trimHistory(List<AgentMessage> history, int maxChars) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        java.util.ArrayDeque<AgentMessage> kept = new java.util.ArrayDeque<>();
        int used = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            AgentMessage message = history.get(i);
            int cost = message.content() == null ? 0 : message.content().length();
            if (!kept.isEmpty() && used + cost > maxChars) {
                break;
            }
            kept.addFirst(message);
            used += cost;
        }
        return new ArrayList<>(kept);
    }

    /** 原始记录（带 stepNo 等字段），给界面展示 trace 用 */
    public List<AgentMessageLog> loadLogs(Long conversationId) {
        return messageLogMapper.selectByConversation(conversationId);
    }

    // ==================== 长期存续的写作会话 ====================

    /** 长期写作会话的规范标题 */
    private static final String WRITER_TITLE = "写作会话（跨章存续）";

    /**
     * 取本书的写作会话，没有就建一个。
     *
     * <p><b>整本书一个，跨章存续</b> —— 这不是省事，是需求：
     * 副 Agent 要能在写第 14 章时记得"上次你不喜欢太多心理描写"、
     * 记得你上一章提过的那些写作约束。每次写作新开一段对话，
     * 它就永远是个只有当前这一章记忆的写手。
     *
     * <p>取的是<b>最近使用过的那一段</b>（列表按 updated_at 倒序）：
     * 早期版本每次写作都新开一段，库里因此可能已经攒下多段；
     * 选最近在用的那段，能尽量把已有的对话记忆接续上，而不是从一段空会话重来。
     */
    @Transactional
    public AgentConversation findOrCreateWriter(Long projectId) {
        List<AgentConversation> existing = list(projectId, AgentRole.WRITER);
        if (!existing.isEmpty()) {
            AgentConversation conversation = existing.get(0);
            // 顺手把标题规范一下：旧标题是"写「第十三章 追命剑」"这种按次生成的，
            // 作者看到它会以为这是"写第十三章"的一次性会话，而不是整本书共用的
            if (!WRITER_TITLE.equals(conversation.getTitle())) {
                conversation.setTitle(WRITER_TITLE);
                conversationMapper.updateById(conversation);
            }
            return conversation;
        }
        return create(projectId, AgentRole.WRITER, WRITER_TITLE);
    }

    /**
     * 给会话改名。
     *
     * <p>标题原本是从首条消息里截出来的，往往又长又不像人话（"我想让主角在第十三章…"）。
     * 作者自己起的名字，才是他之后找得回来的那一个。
     */
    @Transactional
    public AgentConversation rename(Long conversationId, String title) {
        AgentConversation conversation = require(conversationId);
        String clean = StringUtils.hasText(title) ? title.trim() : "未命名对话";
        // 侧栏只有那么宽，太长的标题除了撑破布局没有别的用处
        conversation.setTitle(clean.length() > 60 ? clean.substring(0, 60) : clean);
        conversationMapper.updateById(conversation);
        return conversation;
    }

    /**
     * 删除一段会话（连同它的消息与方案）。
     *
     * <p>工作区树会越积越长 —— 试验性的对话、走过的死胡同、重复的尝试，
     * 没有删除出口的话它们会永久占着位置，把真正要用的那几段埋掉。
     *
     * <p>注意：写作会话是长期复用的那一段，删掉它等于重置记忆；
     * 下次写作会重新建一段空的（正文、蓝图都不受影响）。
     */
    @Transactional
    public void delete(Long conversationId) {
        require(conversationId);
        messageLogMapper.delete(Wrappers.<AgentMessageLog>lambdaQuery()
                .eq(AgentMessageLog::getConversationId, conversationId));
        planOptionMapper.delete(Wrappers.<AgentPlanOption>lambdaQuery()
                .eq(AgentPlanOption::getConversationId, conversationId));
        conversationMapper.deleteById(conversationId);
        log.info("已删除会话 {}（含其消息与方案）", conversationId);
    }

    /**
     * 清空一段会话的历史消息（保留会话本身）。
     *
     * <p>长期会话的记忆是优点，但作者需要能重置它 —— 比如大方向改了、
     * 或早先说过的某条偏好不再适用。没有这个出口，唯一办法就是重建项目。
     */
    @Transactional
    public void clearMessages(Long conversationId) {
        require(conversationId);
        messageLogMapper.delete(Wrappers.<AgentMessageLog>lambdaQuery()
                .eq(AgentMessageLog::getConversationId, conversationId));
        log.info("已清空会话 {} 的历史消息", conversationId);
    }

    /**
     * 只取"人话"的历史：用户的指令 + 助手的文本回答。
     *
     * <p><b>为什么必须成对跳过，而不是简单过滤</b>：
     * 协议要求 assistant 的 {@code tool_calls} 后面必须紧跟对应的 tool 结果。
     * 如果保留"带工具调用的那一轮"却丢掉它的工具结果，请求会被服务方直接拒绝。
     * 所以这两类一起不要 —— 工具结果本来就又长又是临时的
     * （比如"读了第 3 章全文"），留在历史里只会白白吃掉上下文。
     *
     * <p>模型因此"不记得"某次具体查了什么。这没关系 —— 它需要时能再查一次，
     * 而真正需要长期记住的是用户的偏好与约束，那些都在文本里。
     */
    public List<AgentMessage> loadTextHistory(Long conversationId) {        List<AgentMessage> history = new ArrayList<>();
        for (AgentMessageLog record : loadTextLogs(conversationId)) {
            history.add(new AgentMessage(record.getRole(), record.getContent(), null, null, List.of()));
        }
        return history;
    }

    /**
     * 与 {@link #loadTextHistory} 同一套过滤规则，但保留记录本身（含 id / createdAt）。
     * 前端要把"策划的写作指令"和"对话消息"按时间合并成一条时间线，需要时间戳。
     */
    public List<AgentMessageLog> loadTextLogs(Long conversationId) {
        List<AgentMessageLog> logs = new ArrayList<>();
        for (AgentMessageLog record : messageLogMapper.selectByConversation(conversationId)) {
            if (AgentMessage.ROLE_TOOL.equals(record.getRole())) {
                continue;
            }
            if (record.getToolCallsJson() != null && !record.getToolCallsJson().isBlank()) {
                continue;
            }
            if (record.getContent() == null || record.getContent().isBlank()) {
                continue;
            }
            logs.add(record);
        }
        return logs;
    }

    // ==================== 剧情方案 ====================

    /** 主 Agent 给出的走向方案 */
    public record PlanOptionDraft(String title, String summary, String detail) {
    }

    @Transactional
    public List<AgentPlanOption> savePlanOptions(Long conversationId, Long messageId,
                                                 List<PlanOptionDraft> drafts) {
        /*
         * 新一轮方案覆盖上一轮：先把这段会话里还没被选的旧方案删掉。
         *
         * 为什么不能留着：方案序号每批从 1 开始，旧方案留着会和新方案撞号，
         * "选第 2 条"就可能选到上一批；而且它们会被反复注入上下文，越滚越长。
         * 已被用户选中的旧方案也不留 —— 新一批方案出现，本身就意味着要重新决定。
         */
        planOptionMapper.delete(Wrappers.<AgentPlanOption>lambdaQuery()
                .eq(AgentPlanOption::getConversationId, conversationId));

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
