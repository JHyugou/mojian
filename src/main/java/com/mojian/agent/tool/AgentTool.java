package com.mojian.agent.tool;

import java.util.Map;

/**
 * Agent 工具契约。
 *
 * <p>一个工具 = 三样东西：
 * <ol>
 *   <li>{@link #name()} —— 模型用它指定"我要调哪个"</li>
 *   <li>{@link #description()} —— 给模型看的说明书；写得好不好，直接决定它选得对不对</li>
 *   <li>{@link #parametersSchema()} —— 参数定义，OpenAI tools 规范的 JSON Schema</li>
 * </ol>
 *
 * <p>注意：<b>模型看不到这个接口的实现代码</b>，它只能看到上面三样。
 * 所以工具能不能被正确使用，取决于描述写得清不清楚。
 */
public interface AgentTool {

    String name();

    String description();

    /**
     * 参数的 JSON Schema，例如：
     * <pre>
     * {"type":"object","properties":{"name":{"type":"string","description":"人物名"}},"required":["name"]}
     * </pre>
     */
    Map<String, Object> parametersSchema();

    /**
     * 真正执行。arguments 是模型给出的参数（已从 JSON 解析成 Map）。
     *
     * <p>返回值会作为"观察结果"喂回给模型，所以要用<b>模型能读懂的自然语言</b>，
     * 而不是返回一个对象或状态码。
     */
    String execute(Map<String, Object> arguments);
}
