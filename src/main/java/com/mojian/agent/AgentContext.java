package com.mojian.agent;

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
 */
public record AgentContext(Long projectId) {

    public static AgentContext of(Long projectId) {
        return new AgentContext(projectId);
    }

    public Long requireProjectId() {
        if (projectId == null) {
            throw new IllegalStateException("Agent 上下文缺少 projectId");
        }
        return projectId;
    }
}
