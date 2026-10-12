package com.mojian.generation.llm;

/**
 * 流式回调：片段逐步到达，完成后给出全文，失败时给出原因。
 */
public interface StreamCallback {

    void onDelta(String delta);

    /** 思维链片段（reasoning_content）：推理模型"思考中"的可见化，默认忽略 */
    default void onReasoning(String delta) {}

    void onComplete(String fullText);

    void onError(Throwable error);
}
