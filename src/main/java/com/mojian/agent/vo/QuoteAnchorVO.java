package com.mojian.agent.vo;

/**
 * 正文引用在章节中的精确位置。
 *
 * <p>baseVersionNo 是引用时的版本号；写作任务应用引用前必须校验它，
 * 章节已经被改过就拒绝，避免拿旧引用来覆盖新正文。
 */
public record QuoteAnchorVO(Long chapterId, int chapterNo, String chapterTitle,
                            int baseVersionNo, int paragraphStart, int paragraphEnd,
                            int charStart, int charEnd, String quotedText,
                            String paragraphText) {
}
