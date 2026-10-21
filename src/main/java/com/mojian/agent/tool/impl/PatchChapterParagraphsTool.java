package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.material.ChapterIndex;
import com.mojian.agent.tool.AgentTool;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterParagraphs;
import com.mojian.chapter.service.ChapterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 按段落修改正文：引用修改的唯一出口。
 *
 * <p>它把"第几段 + 该段原文 + 基于哪个版本"作为校验条件：
 * 版本不一致或段落原文已经变了，工具直接失败，不会把改动写到错误的段落。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PatchChapterParagraphsTool implements AgentTool {

    private final ChapterIndex chapterIndex;
    private final ChapterService chapterService;

    @Override
    public String name() {
        return "patch_chapter_paragraphs";
    }

    @Override
    public String description() {
        return "按段落精确修改正文，适合引用修改/局部修订。"
                + "必须传 chapterNo、baseVersionNo（引用时看到的版本号）和 edits；"
                + "每一处 edit 要带 paragraphIndex、expectedText、newText。"
                + "只要版本或某段 expectedText 与当前正文不一致，工具会拒绝，要求重新读取或重新引用。"
                + "不要用它修改未引用的段落；整章重写才用 save_chapter_draft。";
    }

    @Override
    public List<String> roles() {
        return List.of("WRITER");
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "chapterNo", Map.of("type", "integer",
                                "description", "目标章节序号，从 1 开始"),
                        "baseVersionNo", Map.of("type", "integer",
                                "description", "引用/读取时的章节版本号；必须与当前版本一致"),
                        "edits", Map.of("type", "array",
                                "description", "要替换的段落列表",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", Map.of(
                                                "paragraphIndex", Map.of("type", "integer",
                                                        "description", "段落序号，从 1 开始"),
                                                "expectedText", Map.of("type", "string",
                                                        "description", "该段当前完整原文，用于校验定位"),
                                                "newText", Map.of("type", "string",
                                                        "description", "替换后的段落原文；可以为空表示删除该段")),
                                        "required", List.of("paragraphIndex", "expectedText", "newText")))),
                "required", List.of("chapterNo", "baseVersionNo", "edits"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Integer chapterNo = intOf(arguments.get("chapterNo"));
        Integer baseVersionNo = intOf(arguments.get("baseVersionNo"));
        if (chapterNo == null || chapterNo < 1) {
            return "错误：chapterNo 必须是 ≥1 的整数。";
        }
        if (baseVersionNo == null) {
            return "错误：patch_chapter_paragraphs 需要 baseVersionNo（引用时看到的版本号）。";
        }
        Optional<Chapter> target = chapterIndex.byNumber(projectId, chapterNo);
        if (target.isEmpty()) {
            return "错误：本书没有第 " + chapterNo + " 章。";
        }
        Chapter chapter = target.get();
        int currentVersion = chapter.getCurrentVersionNo() == null ? 0 : chapter.getCurrentVersionNo();
        if (currentVersion != baseVersionNo) {
            return "错误：第 " + chapterNo + " 章当前是版本 " + currentVersion
                    + "，不是引用时的版本 " + baseVersionNo + "。请重新读取正文并重新引用，不能直接修改。";
        }

        Object rawEdits = arguments.get("edits");
        if (!(rawEdits instanceof List<?> list) || list.isEmpty()) {
            return "错误：edits 不能为空。";
        }
        List<ChapterParagraphs.Paragraph> paragraphs = ChapterParagraphs.split(chapter.getContent());
        List<Edit> edits = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                return "错误：edits 里每一项都必须是对象。";
            }
            Integer paragraphIndex = intOf(map.get("paragraphIndex"));
            String expectedText = text(map.get("expectedText"));
            String newText = text(map.get("newText"));
            if (paragraphIndex == null || paragraphIndex < 1 || paragraphIndex > paragraphs.size()) {
                return "错误：段落 " + paragraphIndex + " 不存在；本章共 " + paragraphs.size() + " 段。";
            }
            if (!seen.add(paragraphIndex)) {
                return "错误：同一段不能在一次调用里修改两次（第 " + paragraphIndex + " 段）。";
            }
            if (!StringUtils.hasText(expectedText)) {
                return "错误：第 " + paragraphIndex + " 段缺少 expectedText，无法校验定位。";
            }
            ChapterParagraphs.Paragraph paragraph = paragraphs.get(paragraphIndex - 1);
            if (!paragraph.text().trim().equals(expectedText.trim())) {
                return "错误：第 " + paragraphIndex + " 段原文已经变化，不能按旧引用修改。"
                        + "请重新读取该段并重新引用。";
            }
            edits.add(new Edit(paragraphIndex, paragraph.start(), paragraph.end(), newText));
        }
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        StringBuilder content = new StringBuilder(chapter.getContent() == null ? "" : chapter.getContent());
        for (Edit edit : edits) {
            content.replace(edit.start(), edit.end(), edit.newText());
        }

        ChapterSaveRequest request = new ChapterSaveRequest();
        request.setTitle(chapter.getTitle());
        request.setOutlineNodeId(chapter.getOutlineNodeId());
        request.setStatus(chapter.getStatus());
        request.setContent(content.toString());
        request.setCreateVersion(true);
        request.setRemark("按引用段落修改");
        Chapter saved = chapterService.update(chapter.getId(), request, "AI");
        log.info("按段落修改第 {} 章 {} 段，新版本 {}", chapterNo, edits.size(), saved.getCurrentVersionNo());
        return "已按引用修改第 " + chapterNo + " 章《" + saved.getTitle() + "》的 "
                + edits.size() + " 个段落，新版本号 " + saved.getCurrentVersionNo() + "。\n"
                + "请核对修改后的段落，不要在回复里复述整章。";
    }

    private Integer intOf(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record Edit(int paragraphIndex, int start, int end, String newText) {
    }
}
