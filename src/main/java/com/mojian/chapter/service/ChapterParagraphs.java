package com.mojian.chapter.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 章节正文的段落切分口径。
 *
 * <p>引用锚点、段落级补丁和写作材料必须共用同一套切分规则，否则
 * “第 3 段”在前端、后端和模型眼里会指向不同位置。
 * 约定：连续空行（空行里允许空格/Tab）分隔段落，段内单换行不拆段。
 */
public final class ChapterParagraphs {

    private static final Pattern SEPARATOR = Pattern.compile("\\r?\\n[ \\t]*\\r?\\n");

    private ChapterParagraphs() {
    }

    public record Paragraph(int index, int start, int end, String text) {
    }

    public static List<Paragraph> split(String content) {
        List<Paragraph> result = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return result;
        }
        Matcher matcher = SEPARATOR.matcher(content);
        int cursor = 0;
        int index = 1;
        while (matcher.find()) {
            index = add(result, content, cursor, matcher.start(), index);
            cursor = matcher.end();
        }
        add(result, content, cursor, content.length(), index);
        return result;
    }

    public static Optional<Paragraph> byIndex(String content, int paragraphIndex) {
        List<Paragraph> paragraphs = split(content);
        if (paragraphIndex < 1 || paragraphIndex > paragraphs.size()) {
            return Optional.empty();
        }
        return Optional.of(paragraphs.get(paragraphIndex - 1));
    }

    private static int add(List<Paragraph> result, String content, int rawStart, int rawEnd, int index) {
        int start = rawStart;
        int end = rawEnd;
        while (start < end && Character.isWhitespace(content.charAt(start))) start++;
        while (end > start && Character.isWhitespace(content.charAt(end - 1))) end--;
        if (start >= end) {
            return index;
        }
        result.add(new Paragraph(index, start, end, content.substring(start, end)));
        return index + 1;
    }
}
