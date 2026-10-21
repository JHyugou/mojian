package com.mojian.agent.service;

import com.mojian.agent.dto.QuoteAnchorRequest;
import com.mojian.agent.material.ChapterIndex;
import com.mojian.agent.vo.QuoteAnchorVO;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterParagraphs;
import com.mojian.chapter.service.ChapterService;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/** 把正文选区解析成章节段落锚点；同时负责跨版本定位校验的基准数据。 */
@Service
@RequiredArgsConstructor
public class QuoteAnchorService {

    private final ChapterService chapterService;
    private final ChapterIndex chapterIndex;

    public QuoteAnchorVO locate(Long chapterId, QuoteAnchorRequest request) {
        if (request == null || !StringUtils.hasText(request.quotedText())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "引用的文字不能为空");
        }
        Chapter chapter = chapterService.requireById(chapterId);
        String content = chapter.getContent();
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "这一章还没有正文，无法定位引用");
        }
        String quoted = request.quotedText();
        Span span = findSpan(content, quoted,
                request.contextBefore(), request.contextAfter());
        if (span == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "在当前章节正文里找不到这段文字，可能引用已过期；请重新选中后再引用");
        }
        List<ChapterParagraphs.Paragraph> paragraphs = ChapterParagraphs.split(content);
        ChapterParagraphs.Paragraph startParagraph = null;
        ChapterParagraphs.Paragraph endParagraph = null;
        for (ChapterParagraphs.Paragraph paragraph : paragraphs) {
            if (paragraph.start() <= span.start() && span.start() < paragraph.end()) {
                startParagraph = paragraph;
            }
            if (paragraph.start() < span.end() && span.end() <= paragraph.end()) {
                endParagraph = paragraph;
            }
        }
        if (startParagraph == null || endParagraph == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "无法把引用映射到章节段落");
        }
        int chapterNo = chapterIndex.numberOf(chapter.getProjectId(), chapter.getId());
        return new QuoteAnchorVO(chapter.getId(), chapterNo, chapter.getTitle(),
                chapter.getCurrentVersionNo() == null ? 0 : chapter.getCurrentVersionNo(),
                startParagraph.index(), endParagraph.index(),
                span.start(), span.end(), quoted, startParagraph.text());
    }

    /**
     * 先按原文精确查找；选区里有 Markdown 渲染造成的空白差异时，再按折叠空白查找。
     * 多处命中时用上下文打分，避免“同样一句话出现三次”时锚错位置。
     */
    private Span findSpan(String content, String quoted, String before, String after) {
        Span best = null;
        int bestScore = -1;
        int from = 0;
        while (true) {
            int index = content.indexOf(quoted, from);
            if (index < 0) break;
            int score = contextScore(content, index, index + quoted.length(), before, after);
            if (score > bestScore) {
                best = new Span(index, index + quoted.length());
                bestScore = score;
            }
            from = index + Math.max(1, quoted.length());
        }
        if (best != null && bestScore > 0) {
            return best;
        }
        // 空白折叠匹配：用于渲染后的选区与 Markdown 原文存在空行/缩进差异的情况
        Normalized normalized = normalize(content);
        String normalizedQuote = normalizeSpaces(quoted);
        if (!normalizedQuote.isEmpty()) {
            int index = normalized.text.indexOf(normalizedQuote);
            if (index >= 0) {
                int end = index + normalizedQuote.length() - 1;
                if (best == null) {
                    return new Span(normalized.map[index], normalized.map[end] + 1);
                }
            }
        }
        return best;
    }

    private int contextScore(String content, int start, int end, String before, String after) {
        int score = 0;
        if (StringUtils.hasText(before)) {
            String actual = content.substring(Math.max(0, start - 100), start);
            if (actual.endsWith(before)) score += 4;
            else if (before.length() > 12 && actual.endsWith(before.substring(before.length() - 12))) score += 2;
        }
        if (StringUtils.hasText(after)) {
            String actual = content.substring(end, Math.min(content.length(), end + 100));
            if (actual.startsWith(after)) score += 4;
            else if (after.length() > 12 && actual.startsWith(after.substring(0, 12))) score += 2;
        }
        return score;
    }

    private Normalized normalize(String content) {
        StringBuilder text = new StringBuilder(content.length());
        int[] map = new int[content.length()];
        boolean pendingSpace = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c)) {
                pendingSpace = true;
                continue;
            }
            if (pendingSpace && text.length() > 0) {
                map[text.length()] = i;
                text.append(' ');
            }
            pendingSpace = false;
            map[text.length()] = i;
            text.append(c);
        }
        return new Normalized(text.toString(), map);
    }

    private String normalizeSpaces(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ");
    }

    private record Span(int start, int end) {
    }

    private record Normalized(String text, int[] map) {
    }
}
