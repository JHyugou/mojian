package com.mojian.agent.dto;

/**
 * 写作任务携带的正文引用锚点。
 *
 * <p>它不是普通上下文：后端会在启动任务前校验章节版本和段落原文，
 * 写作 Agent 只能通过 patch_chapter_paragraphs 修改这些段落。
 */
public record WriterQuoteRequest(Long chapterId, Integer baseVersionNo,
                                 Integer paragraphStart, Integer paragraphEnd,
                                 Integer charStart, Integer charEnd,
                                 String quotedText) {
}
