package com.mojian.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojian.agent.llm.AgentLlmClient;
import com.mojian.agent.llm.AgentLlmResponse;
import com.mojian.agent.llm.AgentMessage;
import com.mojian.agent.llm.AgentToolCall;
import com.mojian.agent.llm.TokenUsage;
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
 *
 * <p>它同时负责累计各轮 {@code usage} —— 一次运行到底花了多少 token，
 * 只有循环自己知道（它见过每一轮的返回）。
 */
@Slf4j
public class AgentLoopService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 单次工具结果回灌的上限。
     *
     * <p>工具结果会留在消息里跟着后续每一轮请求一起发，而且是<b>累积</b>的：
     * 模型多调几次 query_chapter，几万字原文就会永久占住上下文。
     * 超过上限就截断并明确告知模型"原文更长、可再取"——它需要时可以再查一次。
     */
    private static final int MAX_TOOL_RESULT_CHARS = 12_000;

    private final AgentLlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;

    public AgentLoopService(AgentLlmClient llmClient, ToolRegistry toolRegistry, AgentProperties properties) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    /** 用全局默认步数运行（不区分角色时的兜底） */
    public AgentResult run(AgentContext context, String systemPrompt, String userInput) {
        return run(context, systemPrompt, List.of(), userInput, properties.getMaxSteps());
    }

    /** 指定步数、不带历史 */
    public AgentResult run(AgentContext context, String systemPrompt, String userInput, int maxSteps) {
        return run(context, systemPrompt, List.of(), userInput, maxSteps);
    }

    /**
     * 跑一轮完整的 Agent 对话。
     *
     * @param context      业务上下文（项目 id、角色、会话 id），由调用方注入
     * @param systemPrompt 系统提示词（规矩，策划 Agent 的材料也注在里面）
     * @param history      之前几轮的消息（来自会话持久化）。它们排在系统提示词之后、
     *                     本轮用户输入之前 —— 顺序就是模型理解对话的顺序，不能乱
     * @param userInput    用户这次的问题
     * @param maxSteps     最大步数 —— 由调用方按角色传入（策划与写作需要的步数不一样）
     */
    public AgentResult run(AgentContext context, String systemPrompt, List<AgentMessage> history,
                           String userInput, int maxSteps) {
        return run(context, systemPrompt, history, userInput, maxSteps, AgentRunListener.NOOP);
    }

    /**
     * 带实时回调的版本 —— 界面靠它做到"边跑边看见"。
     *
     * @param listener 过程回调；传 {@link AgentRunListener#NOOP} 等价于不关心过程
     */
    public AgentResult run(AgentContext context, String systemPrompt, List<AgentMessage> history,
                           String userInput, int maxSteps, AgentRunListener listener) {
        AgentRunListener sink = listener == null ? AgentRunListener.NOOP : listener;
        // messages 就是那个"每次发给模型的包裹"，它会在循环里一条条长大
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system(systemPrompt));
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }
        messages.add(AgentMessage.user(userInput));

        List<AgentStep> trace = new ArrayList<>();
        TokenUsage totalUsage = TokenUsage.NONE;

        for (int step = 1; step <= maxSteps; step++) {
            /*
             * 用户点了终止。
             *
             * 为什么必须在"每一轮开始前"查一次，而不是等模型调用自己抛中断：
             * 一次模型调用可能跑一两分钟且持续计费。等到那时候才停，
             * 用户按下终止后账单还在涨 —— 那不叫终止，叫"排队等着停"。
             */
            if (Thread.currentThread().isInterrupted() || sink.cancelled()) {
                log.info("[{}] 任务被终止（第 {} 轮之前），已跑 {} 步",
                        context.agentRole(), step, step - 1);
                return AgentResult.cancelled(trace, step - 1, totalUsage);
            }

            log.debug("[{}] Agent 第 {} 轮，当前包裹 {} 条", context.agentRole(), step, messages.size());
            sink.onStepStart(step);

            // ① 想：问模型下一步干什么（流式回调透传下去，界面上能看到字一个个出来）
            /*
             * 模型调用期间可能被叫停（读流循环会抛出来）。
             * 这里要把它翻译成"被取消"而不是"出错了" —— 否则用户主动停的，
             * 界面上却显示一条失败记录。
             */
            AgentLlmResponse response;
            try {
                // 只把该角色能用的工具发给模型：角色边界在工具层落实，不要靠提示词自律
                response = llmClient.chat(context, List.copyOf(messages),
                        toolRegistry.forRole(context.agentRole()), sink.asLlmListener());
            } catch (com.mojian.common.exception.BusinessException e) {
                if (sink.cancelled()) {
                    log.info("[{}] 模型调用被用户叫停（第 {} 轮），已生成的部分丢弃", context.agentRole(), step);
                    return AgentResult.cancelled(trace, step - 1, totalUsage);
                }
                throw e;
            }

            // 自愈：流式解析偶尔会拿到"没有名字的工具调用"（服务方的流式结构和预期不一致时）。
            // 这种情况绝不能把"工具不存在"这种假错误回传给模型 —— 它只会反复重试，白烧钱又转不出结果。
            // 退回非流式重试一次才是正解：功能不受影响，只是这一轮用户看不到逐字效果。
            if (hasNamelessToolCall(response)) {
                log.warn("[{}] 流式解析得到的工具调用没有名字，本轮退回非流式重试",
                        context.agentRole());
                response = llmClient.chat(context, List.copyOf(messages),
                        toolRegistry.forRole(context.agentRole()));
            }

            totalUsage = totalUsage.plus(response.usage());

            // 出口①：模型不再要求调工具 = 它认为干完了
            if (!response.wantsTools()) {
                log.debug("[{}] Agent 第 {} 轮结束：模型不再调工具，累计 {}", context.agentRole(), step, totalUsage.describe());
                return AgentResult.finished(response.content(), trace, step, totalUsage);
            }

            // 把模型这一轮的意图记进包裹。不记的话，下一轮它不记得自己"说过要调工具"，
            // 之后收到的 tool 结果就对不上号了
            messages.add(AgentMessage.assistant(response.content(), response.toolCalls()));

            // ② 做 + ③ 看：一轮可以要求调多个工具，按顺序执行
            for (AgentToolCall call : response.toolCalls()) {
                /*
                 * 动手之前再看一次中断。
                 *
                 * 只在"每轮开始前"检查是不够的：模型调用本身要跑几十秒，
                 * 用户完全可能在这期间点终止。那种情况下模型还是会返回结果，
                 * 而如果这里不查，工具照跑 —— 用户按下"停下"之后
                 * 还会看到书被改动一次，那是最让人意外的行为。
                 */
                if (Thread.currentThread().isInterrupted() || sink.cancelled()) {
                    log.info("[{}] 工具执行前发现已终止，跳过本轮剩余 {} 个工具调用",
                            context.agentRole(), response.toolCalls().size());
                    return AgentResult.cancelled(trace, step, totalUsage);
                }

                sink.onToolCall(call.name(), call.arguments());
                String result = executeTool(context, call);
                sink.onToolResult(call.name(), result);
                // 观察结果回填 —— 模型下一轮就是靠这个判断"刚才那步成不成"
                messages.add(AgentMessage.tool(call.id(), result));
                trace.add(new AgentStep(step, call.name(), call.arguments(), result));            }
        }

        // 出口②：刹车
        log.warn("[{}] Agent 达到最大步数 {} 被强制停止", context.agentRole(), maxSteps);
        return AgentResult.maxSteps(trace, maxSteps, totalUsage);
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
            return "错误：没有名为「" + call.name() + "」的工具。可用工具：" + availableToolNames(context.agentRole());
        }
        try {
            Map<String, Object> arguments = parseArguments(call.arguments());
            String result = tool.execute(context, arguments);
            String bounded = truncateToolResult(result);
            log.debug("工具 {} 执行完成，返回 {} 字", call.name(), bounded == null ? 0 : bounded.length());
            return bounded;
        } catch (Exception e) {
            log.warn("工具 {} 执行失败: {}", call.name(), e.getMessage());
            return "错误：工具「" + call.name() + "」执行失败（" + e.getMessage()
                    + "）。请换一种方式，或者直接说明你无法完成。";
        }
    }

    /** 工具结果过长时截断，并告诉模型"可以再取一次" */
    private String truncateToolResult(String result) {
        if (result == null || result.length() <= MAX_TOOL_RESULT_CHARS) {
            return result;
        }
        return result.substring(0, MAX_TOOL_RESULT_CHARS)
                + "\n……（结果过长，已截断。需要后续内容时请换一个更精确的查询条件，或分段再取一次）";
    }

    /**
     * 是否出现了"没有名字的工具调用"。
     *
     * <p>这是个不该存在的状态：模型要求调工具，却没说调哪个。真实成因通常是
     * 服务方的流式结构和预期不一致（解析没拿到函数名）。识别出来是为了触发自愈重试，
     * 而不是把"工具不存在"当成模型的错回传给它。
     */
    private boolean hasNamelessToolCall(AgentLlmResponse response) {
        return response.wantsTools() && response.toolCalls().stream()
                .anyMatch(call -> call.name() == null || call.name().isBlank());
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

    private String availableToolNames(String role) {
        return toolRegistry.forRole(role).stream().map(AgentTool::name).toList().toString();
    }
}
