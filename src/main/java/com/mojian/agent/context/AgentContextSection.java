package com.mojian.agent.context;

/**
 * 一段待注入模型上下文的材料。
 *
 * <p>组装器只认识这个结构，不认识数据库 —— 取数是调用方的事，
 * 组装是纯逻辑，所以能被彻底测透。
 *
 * @param kind     类型（摘要 / 人物 / 设定 / 原文），用于报表与裁剪提示
 * @param title    标题，会渲染成 {@code 【标题】} 给模型看
 * @param content  正文
 * @param priority 优先级：<b>数字越小越重要</b>，越先装填、越不容易被裁
 * @param policy   装不下时怎么办
 */
public record AgentContextSection(String kind, String title, String content,
                                  int priority, ContextSectionPolicy policy) {

    /** 必需材料：永不裁剪，装不下就报错 */
    public static AgentContextSection required(String title, String content) {
        return new AgentContextSection("必需", title, content, Integer.MIN_VALUE, ContextSectionPolicy.REQUIRED);
    }

    /** 可选材料：按优先级装填，装不下按下述策略处理 */
    public static AgentContextSection optional(String kind, String title, String content,
                                               int priority, ContextSectionPolicy policy) {
        return new AgentContextSection(kind, title, content, priority, policy);
    }

    public int estimateTokens() {
        return AgentTokenEstimator.estimate(content);
    }

    public boolean required() {
        return policy == ContextSectionPolicy.REQUIRED;
    }

    /** 截断到指定 token 数（预留出截断提示语的空间） */
    public AgentContextSection truncateTo(int maxTokens) {
        int keep = Math.max(0, maxTokens - AgentTokenEstimator.TRUNCATE_MARKER.length());
        if (content.length() <= keep) {
            return this;
        }
        return new AgentContextSection(kind, title,
                content.substring(0, keep) + AgentTokenEstimator.TRUNCATE_MARKER, priority, policy);
    }
}
