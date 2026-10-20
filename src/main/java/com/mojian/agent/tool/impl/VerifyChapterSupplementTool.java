package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.entity.ChapterSupplement;
import com.mojian.agent.service.ChapterSupplementService;
import com.mojian.agent.tool.AgentTool;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 策划确认一条「待核验」补充要求已经落实。
 *
 * <p>只有作者在偏好设置里允许时才开放；关闭后只能由作者在写作页手动确认。
 * 该工具只确认状态，不改正文。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VerifyChapterSupplementTool implements AgentTool {

    private final ChapterSupplementService supplementService;
    private final ChapterService chapterService;

    @Override
    public String name() {
        return "verify_chapter_supplement";
    }

    @Override
    public String description() {
        return "确认一条「待核验」的章节补充要求已经在正文里落实（把状态改为已核验）。"
                + "supplementId 是 review_chapter 输出里的补充要求记录 ID；"
                + "只对状态为待核验的条目有效。是否允许使用由作者设置控制；"
                + "文章还有待执行/执行中/待核验的补充要求时，不能把章节标为 COMPLETED。";
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
                        "supplementId", Map.of("type", "integer",
                                "description", "补充要求记录 ID（review_chapter 输出里可见）"),
                        "reason", Map.of("type", "string",
                                "description", "为什么确认已落实（可选）")),
                "required", List.of("supplementId"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        if (!context.allowSupplementVerify()) {
            return "错误：作者已在偏好设置里关闭「策划可确认补充要求」，你不能确认落实。"
                    + "请让作者在写作页手动确认。";
        }
        Long projectId = context.requireProjectId();
        Long supplementId = longOf(arguments.get("supplementId"));
        String reason = text(arguments.get("reason"));
        if (supplementId == null) {
            return "错误：必须提供 supplementId（review_chapter 输出里的记录 ID）。";
        }
        ChapterSupplement supplement = supplementService.require(supplementId);
        if (!projectId.equals(supplement.getProjectId())) {
            return "错误：这条补充要求不属于当前项目，不能确认。";
        }
        if (!"SUBMITTED".equals(supplement.getStatus())) {
            return "错误：这条补充要求当前状态是「" + statusLabel(supplement.getStatus())
                    + "」，只有待核验的条目可以确认落实。";
        }
        Chapter chapter = chapterService.requireById(supplement.getChapterId());
        supplementService.verify(chapter.getId(), supplementId);
        log.info("策划确认补充要求 {} 已落实（章节 {}，理由：{}）", supplementId, chapter.getId(), reason);
        return "已确认补充要求 #" + supplementId + " 已落实。\n"
                + "所属章节：「" + chapter.getTitle() + "」。"
                + (StringUtils.hasText(reason) ? "\n确认理由：" + reason : "")
                + "\n请告诉作者这次确认；不要再重复确认同一条。";
    }

    private String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING" -> "待执行";
            case "EXECUTING" -> "执行中";
            case "SUBMITTED" -> "待核验";
            case "APPLIED" -> "已核验";
            default -> status;
        };
    }

    private Long longOf(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
