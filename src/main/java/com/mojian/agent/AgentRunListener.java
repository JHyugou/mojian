package com.mojian.agent;

import com.mojian.agent.llm.LlmStreamListener;

/**
 * Agent 运行过程的实时回调 —— 让"边跑边看见"成为可能。
 *
 * <p>为什么需要它：写作任务一轮要两三分钟，且几乎全部时间都在生成正文。
 * 不回调的话，用户只能盯着空白等。有了它，界面可以做到：
 * <pre>
 *   [1/2] 正在准备材料…
 *   [2/2] 正在写正文…   午后日头把官道晒得发白。碎石烫脚…   ← 逐字出现
 *         ↳ 调用了 save_chapter_draft
 *   完成，共 5000 字
 * </pre>
 *
 * <p>所有方法都有默认空实现：不关心某个事件就不实现它。
 * 这也让"不支持流式的实现"可以原样工作 —— 流式是优化，不是必需能力。
 */
public interface AgentRunListener {

    AgentRunListener NOOP = new AgentRunListener() {
    };

    /** 新一轮开始 */
    default void onStepStart(int step) {
    }

    /** 模型正在输出的正文增量 */
    default void onAssistantDelta(String delta) {
    }

    /**
     * 工具参数里"大段内容"的增量（例如正在写的正文）。
     *
     * <p>正文不走 {@link #onAssistantDelta} —— 它装在工具参数里。
     * 不把它接出来，写作时界面就会在模型写作的一两分钟里一直空着。
     */
    default void onToolContentDelta(String toolName, String delta) {
    }

    /** 模型的思维链增量（推理模型会先想一段）。可以拿它显示"正在思考…" */
    default void onReasoning(String delta) {
    }

    /** 模型开始准备调用工具（此时工具名可能还没到齐） */
    default void onToolCallStarted() {
    }

    /** 工具调用已解析完成，即将执行 */
    default void onToolCall(String toolName, String arguments) {
    }

    /** 工具执行完毕 */
    default void onToolResult(String toolName, String result) {
    }

    /**
     * 用户是否已经要求终止。
     *
     * <p><b>为什么不能只靠线程中断</b>：实测 {@code Future.cancel(true)} 那一套不保险 ——
     * HTTP 客户端的读操作不一定响应中断，而中断标记还可能被中途某个
     * "捕获了 InterruptedException 但没恢复标记"的调用悄悄清掉。
     * 结果是用户点了终止，任务照样跑完、照样写进书里。
     *
     * <p>所以终止用一个确定性的标记：调用方自己维护"哪些任务被停了"，
     * 循环每轮、以及每次动手执行工具之前都问一次。它不依赖任何线程语义，
     * 所以行为是可预期的。
     */
    default boolean cancelled() {
        return false;
    }

    /** 把循环事件适配成模型客户端的流式回调 */
    default LlmStreamListener asLlmListener() {
        AgentRunListener self = this;
        return new LlmStreamListener() {
            @Override
            public void onDelta(String delta) {
                self.onAssistantDelta(delta);
            }

            @Override
            public void onReasoning(String delta) {
                self.onReasoning(delta);
            }

            @Override
            public boolean cancelled() {
                return self.cancelled();
            }
            @Override
            public void onToolCallStarted() {
                self.onToolCallStarted();
            }

            @Override
            public void onToolContentDelta(String toolName, String delta) {
                self.onToolContentDelta(toolName, delta);
            }
        };
    }
}
