package com.mojian.agent;

/**
 * Agent 角色。
 *
 * <p>用常量而不是枚举：这两个值要直接落库（{@code agent_profile.role}），
 * 用字符串最省事，也避免 MyBatis 的枚举类型处理器带来的额外配置。
 */
public final class AgentRole {

    /** 策划 Agent：管人物、设定、剧情蓝图 */
    public static final String PLANNER = "PLANNER";

    /** 写作 Agent：把蓝图写成正文 */
    public static final String WRITER = "WRITER";

    private AgentRole() {
    }
}
