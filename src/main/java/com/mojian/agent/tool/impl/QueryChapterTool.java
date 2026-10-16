package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.material.ChapterIndex;
import com.mojian.agent.tool.AgentTool;
import com.mojian.chapter.entity.Chapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 取某一章的完整原文。
 *
 * <p><b>为什么在全量注入的前提下还需要这个工具</b>：
 * 人物、设定、前情摘要都很小，永远在材料里；但<b>章节正文是唯一会失控的一项</b>
 * （每章约 4300 字）。将来材料装不下时，组装器的裁剪顺序是"从最远的章节开始砍"，
 * 而且单章过长时会被截断。这时模型需要一条退路："我想看第 3 章的完整原文。"
 *
 * <p>也就是说：这个工具不服务于"检索"（材料里都有），而是服务于<b>被裁剪后的补取</b>。
 * 这跟"给人手边放一份全稿，同时告诉他档案室在哪一层"是一个道理。
 */
@Component
@RequiredArgsConstructor
public class QueryChapterTool implements AgentTool {

    private final ChapterIndex chapterIndex;

    @Override
    public String name() {
        return "query_chapter";
    }

    @Override
    public String description() {
        return "取某一章的完整原文。"
                + "本书各章正文通常已经包含在资料里；但章节内容最长，"
                + "在材料过大时较早的章节可能被裁掉或截断。"
                + "当你需要某一章的完整原文（而非被截断的片段）时使用本工具。"
                + "参数 chapterNo 是章节序号，从 1 开始。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "chapterNo", Map.of(
                                "type", "integer",
                                "description", "章节序号，从 1 开始（第 1 章就是 1）")),
                "required", List.of("chapterNo"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Integer chapterNo = intOf(arguments.get("chapterNo"));
        if (chapterNo == null) {
            return "错误：必须提供 chapterNo（章节序号，从 1 开始）。";
        }

        Optional<Chapter> found = chapterIndex.byNumber(projectId, chapterNo);
        if (found.isEmpty()) {
            int total = chapterIndex.count(projectId);
            return "错误：本书目前共 " + total + " 章，没有第 " + chapterNo + " 章。";
        }

        Chapter chapter = found.get();
        StringBuilder sb = new StringBuilder();
        sb.append("第 ").append(chapterNo).append(" 章");
        if (StringUtils.hasText(chapter.getTitle())) {
            sb.append("｜").append(chapter.getTitle().trim());
        }
        sb.append('\n');
        sb.append(StringUtils.hasText(chapter.getContent())
                ? chapter.getContent().trim()
                : "（本章尚未开写）");
        return sb.toString();
    }

    private Integer intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
