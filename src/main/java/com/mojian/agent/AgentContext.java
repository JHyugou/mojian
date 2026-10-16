package com.mojian.agent;

import java.util.UUID;

/**
 * 一次 Agent 运行所处的业务上下文。
 *
 * <p>为什么需要它：工具经常要知道"在为哪个项目干活"这类内部信息，
 * 但模型既不知道、也不该决定这些（它看不到数据库，更不该猜 projectId）。
 *
 * <p>所以由调用方（Controller）注入上下文，模型只负责它真正该决定的东西
 * ——比如"要查的人物叫什么名字"。
 *
 * @param projectId 当前正在创作的项目 id
 * @param agentRole 这次运行是谁在干活：{@link AgentRole#PLANNER} 或 {@link AgentRole#WRITER}。
 *                  模型、温度、步数、上下文预算<b>全都按角色取</b> —— 主副 Agent 用不同模型
 *                  这件事，就是靠它落地的
 * @param sessionId 本次运行的会话标识。服务方（OpenCode Go）<b>要求必带</b>这个头，
 *                  它同时用于 prompt 缓存路由：命中缓存后读取单价只有未命中的几十分之一。
 *                  所以这个值必须稳定地贯穿一次运行的所有模型调用；换一次运行就换一个新值。
 */
public record AgentContext(Long projectId, String agentRole, String sessionId) {

    public AgentContext {
        if (agentRole == null || agentRole.isBlank()) {
            agentRole = AgentRole.PLANNER;
        }
    }

    /** 为一次新的 Agent 运行生成上下文（自动分配会话 id） */
    public static AgentContext of(Long projectId, String agentRole) {
        return new AgentContext(projectId, agentRole, "mojian-agent-" + UUID.randomUUID());
    }

    public static AgentContext of(Long projectId, String agentRole, String sessionId) {
        return new AgentContext(projectId, agentRole, sessionId);
    }

    public Long requireProjectId() {
        if (projectId == null) {
            throw new IllegalStateException("Agent 上下文缺少 projectId");
        }
        return projectId;
    }

    public boolean isWriter() {
        return AgentRole.WRITER.equals(agentRole);
    }
}
