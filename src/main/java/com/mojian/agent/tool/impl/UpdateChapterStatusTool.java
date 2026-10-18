package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.material.ChapterIndex;
import com.mojian.agent.tool.AgentTool;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 策划调整章节状态。
 *
 * <p>典型用法：review_chapter 后觉得这一章足够好，把 WRITING 改成 COMPLETED。
 * 只改状态字段，不碰正文；COMPLETED 必须已有正文，避免把空章标记成完成。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpdateChapterStatusTool implements AgentTool {

    private final ChapterIndex chapterIndex;
    private final ChapterService chapterService;

    @Override
    public String name() {
        return "update_chapter_status";
    }

    @Override
    public String description() {
        return "调整章节状态：DRAFT（草稿，不收入成书）/ WRITING（写作中）/ COMPLETED（已完成）。"
                + "典型场景：review_chapter 后觉得这一章已经足够好，把状态改成 COMPLETED。"
                + "只改状态，不改正文；空章节不能标记为 COMPLETED。"
                + "chapterNo 从 1 开始；status 必须是 DRAFT/WRITING/COMPLETED 之一。";
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
                        "status", Map.of("type", "string",
                                "enum", List.of("DRAFT", "WRITING", "COMPLETED"),
                                "description", "目标状态：DRAFT / WRITING / COMPLETED"),
                        "reason", Map.of("type", "string",
                                "description", "调整原因（可选），会告诉用户")),
                "required", List.of("chapterNo", "status"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Integer chapterNo = intOf(arguments.get("chapterNo"));
        String status = text(arguments.get("status")).toUpperCase(Locale.ROOT);
        String reason = text(arguments.get("reason"));
        if (chapterNo == null || chapterNo < 1) {
            return "错误：chapterNo 必须是 ≥1 的整数。";
        }
        if (!"DRAFT".equals(status) && !"WRITING".equals(status) && !"COMPLETED".equals(status)) {
            return "错误：status 只支持 DRAFT / WRITING / COMPLETED。";
        }
        Optional<Chapter> optional = chapterIndex.byNumber(projectId, chapterNo);
        if (optional.isEmpty()) {
            return "错误：本书没有第 " + chapterNo + " 章。";
        }
        Chapter chapter = optional.get();
        if ("COMPLETED".equals(status)
                && !StringUtils.hasText(chapter.getContent())) {
            return "错误：第 " + chapterNo + " 章还没有正文，不能标记为已完成。先让写作 Agent 写出来。";
        }
        if (status.equals(chapter.getStatus())) {
            return "第 " + chapterNo + " 章《" + chapter.getTitle() + "》已经是 " + status + " 状态，无需调整。";
        }

        String before = chapter.getStatus();
        Chapter updated = chapterService.updateStatus(chapter.getId(), status);
        log.info("策划把第 {} 章状态 {} → {}：{}", chapterNo, before, status, reason);
        return "已把第 " + chapterNo + " 章《" + updated.getTitle() + "》的状态从 "
                + statusLabel(before) + "(" + before + ") 改为 "
                + statusLabel(updated.getStatus()) + "(" + updated.getStatus() + ")。\n"
                + "请告诉用户这次状态调整；COMPLETED 表示这一章已通过你的审稿。";
    }

    private String statusLabel(String status) {
        if (status == null) return "未知";
        return switch (status) {
            case "DRAFT" -> "草稿";
            case "WRITING" -> "写作中";
            case "COMPLETED" -> "已完成";
            default -> status;
        };
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
