package com.mojian.agent.tool;

import com.mojian.agent.AgentContext;

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
     * 真正执行。
     *
     * @param context   业务上下文（如当前项目 id）——由代码注入，不由模型提供。
     *                  这样模型只需要关心它该决定的事（参数），内部信息不会泄露给模型、
     *                  也不会被它猜错。
     * @param arguments 模型给出的参数（已从 JSON 解析成 Map）
     * @return 作为"观察结果"喂回给模型的文字。要用<b>模型能读懂的自然语言</b>，
     *         而不是返回对象或状态码。
     */
    String execute(AgentContext context, Map<String, Object> arguments);

    /**
     * 本工具的参数里，哪个字段装的是"大段内容"（比如正文）。
     *
     * <p>用来支持<b>边写边看</b>：这类内容可能有几千字，模型要写上一两分钟。
     * 声明之后，客户端就能在参数还在一片片到达时，把内容随时间抽出来推给界面 ——
     * 否则用户这两分钟只能看到空白，然后正文一次性冒出来。
     *
     * <p>不装大段内容的工具返回 null 即可（默认如此）。
     */
    default String streamingContentField() {
        return null;
    }
}
