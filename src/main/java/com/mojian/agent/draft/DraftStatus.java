package com.mojian.agent.draft;

/**
 * 草稿状态。
 */
public final class DraftStatus {

    /** 等用户确认 */
    public static final String PENDING = "PENDING";

    /** 用户已确认，内容已写入业务表 */
    public static final String ACCEPTED = "ACCEPTED";

    /** 用户放弃 */
    public static final String REJECTED = "REJECTED";

    private DraftStatus() {
    }
}
