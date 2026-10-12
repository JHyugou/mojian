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

    /** 选中文本前文（约 800 字），供改写保持逻辑连贯 */
    private String beforeSelection;

    /** 选中文本后文（约 800 字） */
    private String afterSelection;

    /** 现有正文（续写的衔接基础 / 改写扩缩的语境 / 记忆提炼的原料），超长时中段省略 */
    private String currentContent;

    private OutlineBrief outline;

    private List<CharacterBrief> characters;

    /** 实际注入的设定条目 */
    private List<SettingBrief> settings;

    /** 参与筛选的启用设定总数（展示用：从 N 条中选中 M 条） */
    private Integer settingsConsidered;

    /** 上一章正文尾部（衔接文风与场景；第一章为 null） */
    private String previousContentTail;

    /** 前文章节记忆 */
    private List<MemoryBrief> previousMemories;

    /** 用户确认的剧情方案（正文生成阶段注入） */
    private String confirmedPlan;

    @Data
    public static class OutlineBrief {
        private String name;
        private String summary;
    }

    @Data
    public static class CharacterBrief {
        private String name;
        private String roleIdentity;
        private String description;
        private String exampleDialogue;
    }

    @Data
    public static class SettingBrief {
        private String title;
        private String content;
    }

    @Data
    public static class MemoryBrief {
        private String chapterTitle;
        private String memory;
    }
}
