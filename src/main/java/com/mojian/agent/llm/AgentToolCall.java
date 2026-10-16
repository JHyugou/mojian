package com.mojian.agent.llm;

/**
 * 模型返回的一次"工具调用意图"。
 *
 * <p>关键认知：<b>模型只表达意图，它不会执行任何东西</b>。
 * 真正去查数据库、写文件的是 AgentLoopService 里的代码。
 *
 * @param id        本次调用的唯一 id；回填结果时必须带上它，模型才知道这是哪次调用的结果
 * @param name      工具名
 * @param arguments 参数。OpenAI 规范里是 JSON 字符串（不是对象），所以调用方要自己解析
 */
public record AgentToolCall(String id, String name, String arguments) {
}
