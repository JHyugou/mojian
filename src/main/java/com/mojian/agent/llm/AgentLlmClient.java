package com.mojian.agent.llm;

import com.mojian.agent.AgentContext;
import com.mojian.agent.tool.AgentTool;

import java.util.List;

/**
 * Agent 专用的模型客户端。
 *
 * <p>和墨笺现有的 {@code LlmClient}（一问一答、只发 system+user 两段文本）不同，
 * 它多了两件 Agent 必需的能力：
 * <ol>
 *   <li>可以带<b>多轮消息</b>（那个会一轮轮长大的包裹）</li>
 *   <li>可以带<b>工具清单</b>，并返回结构化的"我要调工具"意图</li>
 * </ol>
 *
 * <p>之所以抽成接口：让 {@code AgentLoopService} 能在完全不联网、不花钱的情况下被测试。
 * 测试里换成假客户端，循环的每条出口都能精确走一遍。
 */
public interface AgentLlmClient {

    /**
     * @param context        本次运行的上下文；其中 {@code sessionId} 会作为请求头发给服务方，
     *                       用于 prompt 缓存路由（命中缓存后读取单价大幅下降）
     * @param messages       完整的对话消息列表（每轮都要整份发过去）
     * @param availableTools 这次允许模型使用的工具（说明书）
     * @return 模型这一轮的回答：要么要求调工具，要么给答案
     */
    AgentLlmResponse chat(AgentContext context, List<AgentMessage> messages, List<AgentTool> availableTools);
}
