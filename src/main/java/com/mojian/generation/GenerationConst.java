package com.mojian.generation;

/**
 * 生成任务类型与状态常量（FR-009 / FR-014）。
 * 存库为字符串，避免枚举序列化与数据库字典耦合。
 */
public final class GenerationConst {

    private GenerationConst() {
    }

    // 任务类型
    public static final String TYPE_PLOT_PLAN = "PLOT_PLAN";
    public static final String TYPE_CONTENT = "CONTENT";
    public static final String TYPE_CONTINUE = "CONTINUE";
    public static final String TYPE_REWRITE = "REWRITE";
    public static final String TYPE_EXPAND_SHRINK = "EXPAND_SHRINK";
    public static final String TYPE_CHAPTER_MEMORY = "CHAPTER_MEMORY";

    // 任务状态
    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
