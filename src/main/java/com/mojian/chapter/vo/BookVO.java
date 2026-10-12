package com.mojian.chapter.vo;

import lombok.Data;

import java.util.List;

/**
 * 成书（FR-020）：按创建顺序装配的项目全书，供阅读视图与导出。
 */
@Data
public class BookVO {

    private String projectName;

    private String genre;

    private String intro;

    /** 章节数（含空章节） */
    private Integer chapterCount;

    /** 全书正文字数（空章节计 0） */
    private Integer totalChars;

    private List<ChapterSection> chapters;

    @Data
    public static class ChapterSection {
        private Long id;
        private String title;
        private String content;
        private Integer chars;
    }
}
