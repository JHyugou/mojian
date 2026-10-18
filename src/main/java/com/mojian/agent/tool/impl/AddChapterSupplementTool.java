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
 * 策划审稿后追加一条补充要求。
 *
 * <p>与章节的 writingRequirement 分开存：每条补充要求独立成行，
 * 下次写作任务作为必备材料注入；写作 Agent 成功写入正文后标记 APPLIED。
 * 不自动开写 —— 只把要求挂到章节上，由作者/写作任务执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AddChapterSupplementTool implements AgentTool {

    private final ChapterIndex chapterIndex;
    private final ChapterSupplementService supplementService;

    @Override
    public String name() {
        return "add_chapter_supplement";
    }

    @Override
    public String description() {
        return "给某一章追加一条审稿补充要求（与本章写作要求分开管理，多条要求互不覆盖）。"
                + "写作 Agent 下次写这一章时会把它作为必备材料执行，成功后标记为已应用。"
                + "必须先 review_chapter 看过正文；一条意见调用一次，不要把多个问题塞进一条。"
                + "chapterNo 从 1 开始；supplement 写具体改法，reason 说明针对什么问题。"
                + "不要自动开写，追加后告诉用户去写作模式执行。";
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
                                "description", "目标章节序号，从 1 开始"),
                        "supplement", Map.of("type", "string",
                                "description", "具体修改要求，写成写作 Agent 可执行的指令"),
                        "reason", Map.of("type", "string",
                                "description", "为什么提这条要求（针对正文里的什么问题）")),
                "required", List.of("chapterNo", "supplement"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Integer chapterNo = intOf(arguments.get("chapterNo"));
        String supplement = text(arguments.get("supplement"));
        String reason = text(arguments.get("reason"));
        if (chapterNo == null || chapterNo < 1) {
            return "错误：chapterNo 必须是 ≥1 的整数。";
        }
        if (!StringUtils.hasText(supplement)) {
            return "错误：supplement 不能为空。";
        }
        if (supplement.length() > 2000) {
            return "错误：单条补充要求太长（上限 2000 字），请拆成具体可执行的几条。";
        }

        Optional<Chapter> optional = chapterIndex.byNumber(projectId, chapterNo);
        if (optional.isEmpty()) {
            return "错误：本书没有第 " + chapterNo + " 章，先 create_chapter 建章。";
        }
        Chapter chapter = optional.get();

        // 完全相同的内容不重复排队；不同问题即使相似也允许分开
        for (ChapterSupplement existing : supplementService.pending(chapter.getId())) {
            if (supplement.equals(existing.getContent() == null ? "" : existing.getContent().trim())) {
                return "这条补充要求已经待执行（记录 ID " + existing.getId() + "），不要重复提交。";
            }
        }

        ChapterSupplement saved = supplementService.create(projectId, chapter.getId(), supplement, reason);
        log.info("策划为第 {} 章追加补充要求 {}：{}", chapterNo, saved.getId(), supplement);
        return "已给第 " + chapterNo + " 章《" + chapter.getTitle() + "》追加补充要求（记录 ID "
                + saved.getId() + "）。\n"
                + "它和本章写作要求分开保存，不会覆盖其他补充要求；"
                + "写作 Agent 下次写这一章时会作为必备材料逐条执行，写入正文后标记为已应用。\n"
                + "请告诉用户这条意见具体是什么，并提醒去写作模式执行（不会自动开写）。";
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

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
