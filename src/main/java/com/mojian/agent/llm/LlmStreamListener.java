package com.mojian.agent.llm;

/**
 * 模型输出的实时回调 —— 让"边生成边看到"成为可能。
 *
 * <p>为什么需要它：写作任务一轮可能要两三分钟，而这期间几乎全部时间都花在
 * <b>模型生成 tool_calls 的参数</b>上（它正在写五千字正文）。
 * 只推"开始了/结束了"这类进度事件没有意义 —— 用户还是要干等两分钟。
 *
 * <p>所有方法都有默认空实现：不关心某个事件就不实现它。
 */
public interface LlmStreamListener {

    LlmStreamListener NOOP = new LlmStreamListener() {
    };

    /** 正文增量。拼起来就是这一步的完整回答 */
    default void onDelta(String delta) {
    }

    /** 思维链增量（推理模型会先输出一段思考）。可以拿它显示"正在思考…" */
    default void onReasoning(String delta) {
    }

    /** 一个工具调用的参数开始输出。工具名可能还没到齐，所以只用于提示"正在准备调用工具" */
    default void onToolCallStarted() {
    }

    /**
     * 工具参数里"大段内容"字段的增量（例如正在写的正文）。
     *
     * <p>正文是通过工具参数传的，不在 {@link #onDelta} 里 ——
     * 不单独抽出来的话，用户会在模型写作的那一两分钟里只看到空白。
     *
     * @param toolName 哪个工具的参数字段（如 save_chapter_draft）
     * @param delta    本次新增的内容
     */
    default void onToolContentDelta(String toolName, String delta) {
    }
}
