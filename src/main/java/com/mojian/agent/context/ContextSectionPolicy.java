package com.mojian.agent.context;

/**
 * 一段上下文材料的装填策略。
 */
public enum ContextSectionPolicy {

    /**
     * 必需材料，永不裁剪：系统提示词、本章蓝图、当前用户指令。
     * 如果连它都装不下，说明配置有问题，直接报错而不是悄悄降级。
     */
    REQUIRED,

    /**
     * 装不下时截断到剩余空间。
     * 适合"最近一章原文"这类：留一部分比一点不留好。
     */
    TRUNCATE_TO_FIT,

    /**
     * 装不下时整段丢弃。
     * 适合"人物档案""设定条目"这类：半条设定比没有更容易误导模型。
     */
    DROP_IF_NOT_FIT
}
