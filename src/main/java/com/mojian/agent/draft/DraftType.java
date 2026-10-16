package com.mojian.agent.draft;

/**
 * 草稿类型 —— 决定"确认后写到哪张业务表"。
 */
public final class DraftType {

    /** 新人物卡 → character_profile */
    public static final String CHARACTER = "CHARACTER";

    /** 新世界观设定 → setting_entry */
    public static final String SETTING = "SETTING";

    /** 剧情蓝图节点 → plot_outline */
    public static final String BLUEPRINT = "BLUEPRINT";

    private DraftType() {
    }
}
