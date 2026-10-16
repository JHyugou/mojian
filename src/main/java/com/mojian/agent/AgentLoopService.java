package com.mojian.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojian.agent.llm.AgentLlmClient;
import com.mojian.agent.llm.AgentLlmResponse;
import com.mojian.agent.llm.AgentMessage;
import com.mojian.agent.llm.AgentToolCall;
import com.mojian.agent.tool.AgentTool;
import com.mojian.agent.tool.ToolRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 循环 —— 整个 Agent 的心脏，全部逻辑就是"想 → 做 → 看 → 再想"。
 *
 * <pre>
 * 用户提问
 *    ↓
 * ┌──────────────────────────────┐
 * │ 想：把包裹发给模型，问下一步     │
 * │   不要调工具了 → 【出口① 完成】  │
 * │   要 ↓                        │
 * │ 做：执行工具（模型自己不执行）    │
 * │   ↓                          │
 * │ 看：结果塞回包裹                │
 * │   ↓                          │
 * │ （转够最大步数）→ 【出口② 刹车】 │
 * └──────────────────────────────┘
 * </pre>
 *
 * <p>这个类刻意<b>不带任何 Spring 注解</b>：核心逻辑不依赖框架，才测得干净。
 */
@Slf4j
public class AgentLoopService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentLlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;

    public AgentLoopService(AgentLlmClient llmClient, ToolRegistry toolRegistry, AgentProperties properties) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    /**
     * 跑一轮完整的 Agent 对话。
     *
     * @param context      业务上下文（当前项目 id 等），由调用方注入
     * @param systemPrompt 系统提示词（规矩）
     * @param userInput    用户这次的问题
     */
    public AgentResult run(AgentContext context, String systemPrompt, String userInput) {
        // messages 就是那个"每次发给模型的包裹"，它会在循环里一条条长大
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system(systemPrompt));
        messages.add(AgentMessage.user(userInput));

        List<AgentStep> trace = new ArrayList<>();

        for (int step = 1; step <= properties.getMaxSteps(); step++) {
            log.debug("Agent 第 {} 轮，当前包裹 {} 条", step, messages.size());

            // ① 想：问模型下一步干什么
            AgentLlmResponse response = llmClient.chat(context, List.copyOf(messages), toolRegistry.all());

            // 出口①：模型不再要求调工具 = 它认为干完了
            if (!response.wantsTools()) {
                log.debug("Agent 第 {} 轮结束：模型不再调工具", step);
                return AgentResult.finished(response.content(), trace, step);
            }

            // 把模型这一轮的意图记进包裹。不记的话，下一轮它不记得自己"说过要调工具"，
            // 之后收到的 tool 结果就对不上号了
            messages.add(AgentMessage.assistant(response.content(), response.toolCalls()));

            // ② 做 + ③ 看：一轮可以要求调多个工具，按顺序执行
            for (AgentToolCall call : response.toolCalls()) {
                String result = executeTool(context, call);
                // 观察结果回填 —— 模型下一轮就是靠这个判断"刚才那步成不成"
                messages.add(AgentMessage.tool(call.id(), result));
                trace.add(new AgentStep(step, call.name(), call.arguments(), result));
            }
        }

        // 出口②：刹车
        log.warn("Agent 达到最大步数 {} 被强制停止", properties.getMaxSteps());
        return AgentResult.maxSteps(trace, properties.getMaxSteps());
    }

    /**
     * 执行工具。
     *
     * <p>这里的设计原则：<b>把所有意外都变成给模型看的文字，而不是抛异常</b>。
     * 工具不存在、参数不是合法 JSON、工具自己报错 —— 全部回传错误信息，
     * 让模型有机会自己纠正。这就是"错误回传"，比直接崩掉强得多。
     */
    private String executeTool(AgentContext context, AgentToolCall call) {
        AgentTool tool = toolRegistry.find(call.name()).orElse(null);
        if (tool == null) {
            return "错误：没有名为「" + call.name() + "」的工具。可用工具：" + availableToolNames();
        }
        try {
            Map<String, Object> arguments = parseArguments(call.arguments());
            String result = tool.execute(context, arguments);
            log.debug("工具 {} 执行完成，返回 {} 字", call.name(), result == null ? 0 : result.length());
            return result;
        } catch (Exception e) {
            log.warn("工具 {} 执行失败: {}", call.name(), e.getMessage());
            return "错误：工具「" + call.name() + "」执行失败（" + e.getMessage()
                    + "）。请换一种方式，或者直接说明你无法完成。";
        }
    }

    /** 模型有时会返回空的 arguments，那是合法的（等价于无参数） */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(raw, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("参数不是合法 JSON: " + raw);
        }
    }

    private String availableToolNames() {
        return toolRegistry.all().stream().map(AgentTool::name).toList().toString();
    }
}
