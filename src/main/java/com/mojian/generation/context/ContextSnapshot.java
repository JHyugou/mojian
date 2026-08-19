package com.mojian.generation.context;

import lombok.Data;

import java.util.List;

/**
 * 一次生成的上下文快照（FR-011）：序列化为 JSON 存入任务表，
 * 可随时回查"当时用了哪些资料"（FR-005 展示要求），且天然不含 API Key。
 */
@Data
public class ContextSnapshot {

    private String projectName;

    /** 项目级文风 + 写作规则 */
    private String styleRules;

    /** 本章在项目中的序号（按创建顺序） */
    private Integer chapterNo;

    private String chapterTitle;

    /** 用户创作指令 */
    private String instruction;

    /** 选中文本（改写/扩缩写用） */
    private String selection;

    private OutlineBrief outline;

    private List<CharacterBrief> characters;

    /** 实际注入的设定条目 */
    private List<SettingBrief> settings;

    /** 参与筛选的启用设定总数（展示用：从 N 条中选中 M 条） */
    private Integer settingsConsidered;

    /** 前文章节记忆 */
    private List<MemoryBrief> previousMemories;

    /** 用户确认的剧情方案（正文生成阶段注入） */
    private String confirmedPlan;

    @Data
    public static class OutlineBrief {
        private String name;
        private String summary;
        private String plotGoal;
        private String conflict;
        private String scene;
        private String mustEvents;
    }

    @Data
    public static class CharacterBrief {
        private String name;
        private String roleIdentity;
        private String personality;
        private String coreGoal;
        private String mainConflict;
        private String relationships;
        private String speechStyle;
        private String exampleDialogue;
        private String currentStatus;
    }

    @Data
    public static class SettingBrief {
        private String title;
        private String content;
    }

    @Data
    public static class MemoryBrief {
        private String chapterTitle;
        private String summary;
        private String unresolvedConflicts;
    }
}
