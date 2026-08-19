package com.mojian.generation.llm;

/**
 * 流式回调：片段逐步到达，完成后给出全文，失败时给出原因。
 */
public interface StreamCallback {

    void onDelta(String delta);

    void onComplete(String fullText);

    void onError(Throwable error);
}
