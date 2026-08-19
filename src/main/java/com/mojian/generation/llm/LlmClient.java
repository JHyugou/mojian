package com.mojian.generation.llm;

/**
 * 模型客户端统一抽象（FR-012）。
 * 业务模块只依赖本接口，不感知具体供应商的请求格式；
 * 测试与无模型演示环境使用 MockLlmClient。
 */
public interface LlmClient {

    /** 同步调用，返回完整文本（用于剧情方案、章节记忆等短生成） */
    String chat(LlmRequest request);

    /**
     * 流式调用（用于正文生成，FR-013）。
     * 回调在调用线程内同步执行；任何异常通过 onError 传出。
     */
    void chatStream(LlmRequest request, StreamCallback callback);
}
