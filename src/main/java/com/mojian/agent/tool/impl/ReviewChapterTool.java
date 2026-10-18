package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.entity.ChapterSupplement;
import com.mojian.agent.material.ChapterIndex;
import com.mojian.agent.service.ChapterSupplementService;
import com.mojian.agent.tool.AgentTool;
import com.mojian.chapter.entity.Chapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 策划审稿：把写作 Agent 的正文、本章写作要求、已有补充要求一起取出来。
 *
 * <p>光给正文不够 —— 审稿要判断"是否满足写作要求、上一轮补充要求有没有落实"，
 * 所以这些材料一并返回。修改意见用 add_chapter_supplement 单独追加。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewChapterTool implements AgentTool {

    private static final int MAX_CONTENT_CHARS = 20_000;

    private final ChapterIndex chapterIndex;
    private final ChapterSupplementService supplementService;

    @Override
    public String name() {
        return "review_chapter";
    }

    @Override
    public String description() {
        return "审阅某一章写作 Agent 写出的正文：返回正文全文、写作要求、已有补充要求与执行状态。"
                + "用户让你看看写得怎么样、或者你准备给写作 Agent 提修改意见时先用它。"
                + "chapterNo 从 1 开始。审完后如果觉得足够好，可以直接给通过结论，不必提补充要求；"
                + "只有确实需要修改时，才用 add_chapter_supplement 逐条追加，不要自己改正文。";
    }

    @Override
    public List<String> roles() {
        return List.of("PLANNER");
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "chapterNo", Map.of("type", "integer",
                                "description", "要审阅的章节序号，从 1 开始")),
                "required", List.of("chapterNo"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Integer chapterNo = intOf(arguments.get("chapterNo"));
        if (chapterNo == null || chapterNo < 1) {
            return "错误：chapterNo 必须是 ≥1 的整数。";
        }
        Optional<Chapter> optional = chapterIndex.byNumber(projectId, chapterNo);
        if (optional.isEmpty()) {
            return "错误：本书没有第 " + chapterNo + " 章。";
        }
        Chapter chapter = optional.get();
        String content = chapter.getContent() == null ? "" : chapter.getContent().trim();
        if (!StringUtils.hasText(content)) {
            return "第 " + chapterNo + " 章《" + chapter.getTitle() + "》还没有正文。"
                    + "先让写作 Agent 写，再来审稿。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("第 ").append(chapterNo).append(" 章《").append(chapter.getTitle()).append("》\n");
        sb.append("状态：").append(chapter.getStatus()).append("｜版本：")
                .append(chapter.getCurrentVersionNo() == null ? 0 : chapter.getCurrentVersionNo())
                .append("｜字数：").append(content.length()).append("\n\n");

        sb.append("【本章写作要求】（这是作者设定的长期写法约束，不是补充要求）\n");
        sb.append(StringUtils.hasText(chapter.getWritingRequirement())
                ? chapter.getWritingRequirement().trim() : "（未设置）").append("\n\n");

        List<ChapterSupplement> supplements = supplementService.listByChapter(chapter.getId());
        sb.append("【已有补充要求】（审稿追加，按条独立；APPLIED 表示写作 Agent 已处理过一次）\n");
        if (supplements.isEmpty()) {
            sb.append("（暂无）\n");
        } else {
            int index = 1;
            for (ChapterSupplement supplement : supplements) {
                sb.append(index++).append(". [")
                        .append("PENDING".equals(supplement.getStatus()) ? "待执行" : "已应用")
                        .append("] [记录 ID ").append(supplement.getId()).append("] ")
                        .append(supplement.getContent().trim()).append('\n');
                if (StringUtils.hasText(supplement.getReason())) {
                    sb.append("   原因：").append(supplement.getReason().trim()).append('\n');
                }
            }
        }
        sb.append('\n');

        sb.append("【正文】\n");
        if (content.length() > MAX_CONTENT_CHARS) {
            sb.append(content, 0, MAX_CONTENT_CHARS)
                    .append("\n……（正文过长，已截断展示前 ").append(MAX_CONTENT_CHARS).append(" 字）");
        } else {
            sb.append(content);
        }
        sb.append("\n\n");
        sb.append("请具体判断：是否满足写作要求、人物/前情是否一致、有没有需要修的地方。");
        sb.append("审稿不是必须提修改：如果这稿已经达标，直接说可以保留/通过，不要调用 add_chapter_supplement。");
        sb.append("只有确实存在具体问题时，才调用 add_chapter_supplement，一条意见一条补充要求；不要自己改正文，也不要只说空泛的好话。");
        sb.append("如果用户明确说某条补充要求不要了，可以用上面的记录 ID 调 remove_chapter_supplement 移除。");
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
