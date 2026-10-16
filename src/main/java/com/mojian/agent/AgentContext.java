package com.mojian.agent;

import java.util.UUID;

/**
 * 一次 Agent 运行所处的业务上下文。
 *
 * <p>为什么需要它：工具经常要知道"在为哪个项目干活"这类内部信息，
 * 但模型既不知道、也不该决定这些（它看不到数据库，更不该猜 projectId）。
 *
 * <p>所以由调用方注入上下文，模型只负责它真正该决定的东西
 * ——比如"要查的人物叫什么名字"。
 *
 * @param projectId      当前正在创作的项目 id
 * @param agentRole      这次运行是谁在干活：{@link AgentRole#PLANNER} 或 {@link AgentRole#WRITER}。
 *                       模型、温度、步数、上下文预算<b>全都按角色取</b>
 * @param conversationId 本次运行挂在哪个持久会话上；工具（如 propose_plot_options）
 *                       要靠它把结果写回正确的地方。可为 null（无会话的一次性运行）
 * @param chapterId      写作任务的目标章节。策划任务为 null。
 *                       写作工具（save_chapter_draft）靠它知道该写进哪一章 ——
 *                       模型不该、也无法提供这个 id
 * @param sessionId      发给服务方的会话标识，用于 prompt 缓存路由。
 *                       <b>它的取值方式直接影响账单</b>：见 {@link #ofConversation}
 */
public record AgentContext(Long projectId, String agentRole, Long conversationId,
                           Long chapterId, String sessionId) {

    public AgentContext {
        if (agentRole == null || agentRole.isBlank()) {
            agentRole = AgentRole.PLANNER;
        }
    }

    /** 一次性运行（没有持久会话，比如调试接口）：每次分配新的 sessionId */
    public static AgentContext of(Long projectId, String agentRole) {
        return new AgentContext(projectId, agentRole, null, null, "mojian-run-" + UUID.randomUUID());
    }

    /**
     * 挂在持久会话上的运行。
     *
     * <p><b>sessionId 由会话 id 派生</b>，而不是每次新生成 —— 这是个真金白银的优化：
     * prompt 缓存是按"会话"在服务方那边路由的。如果每次运行都换一个新 sessionId，
     * 同一段对话的下一轮就没法复用上一轮已经缓存的前缀（而前缀恰恰是最长、最贵的那部分）。
     * 用会话 id 派生，多轮对话里那段不变的材料前缀就能一直命中缓存。
     */
    public static AgentContext ofConversation(Long projectId, String agentRole, Long conversationId) {
        return new AgentContext(projectId, agentRole, conversationId, null,
                "mojian-conv-" + conversationId);
    }

    /** 写作运行的上下文：绑定到具体章节 */
    public static AgentContext forChapter(Long projectId, String agentRole,
                                          Long conversationId, Long chapterId) {
        return new AgentContext(projectId, agentRole, conversationId, chapterId,
                "mojian-chapter-" + chapterId);
    }

    public Long requireProjectId() {
        if (projectId == null) {
            throw new IllegalStateException("Agent 上下文缺少 projectId");
        }
        return projectId;
    }

    /** 需要写回会话的工具靠它取会话 id；没有会话时给出明确报错，而不是写到一个空地方 */
    public Long requireConversationId() {
        if (conversationId == null) {
            throw new IllegalStateException("本次运行没有关联会话，无法写回结果");
        }
        return conversationId;
    }

    /** 写正文的工具靠它取目标章节 */
    public Long requireChapterId() {
        if (chapterId == null) {
            throw new IllegalStateException("本次运行没有指定目标章节");
        }
        return chapterId;
    }

    public boolean hasConversation() {
        return conversationId != null;
    }

    public boolean isWriter() {
        return AgentRole.WRITER.equals(agentRole);
    }
}
