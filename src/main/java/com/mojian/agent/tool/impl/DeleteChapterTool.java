package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.entity.ChapterDeleteRequest;
import com.mojian.agent.material.ChapterIndex;
import com.mojian.agent.service.ChapterDeleteRequestService;
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
 * 写作 Agent 的删除章节工具。
 *
 * <p>删除是不可逆的高风险动作，所以默认不直接删：
 * 创建一个 PENDING 申请，等作者在写作面板确认；
 * 只有作者在偏好设置里显式开启"自动批准删除章节"时，才当场删除。
 * 自动删除也会留下 APPROVED 记录用于审计。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteChapterTool implements AgentTool {

    private final ChapterIndex chapterIndex;
    private final ChapterDeleteRequestService deleteRequestService;

    @Override
    public String name() {
        return "delete_chapter";
    }

    @Override
    public String description() {
        return "申请删除某一章（章节将被移除，正文、版本、记忆一并删除，不可恢复）。"
                + "默认只是创建删除申请，必须由作者在写作面板确认后才会真正删除；"
                + "作者也可以在设置里开启自动批准。chapterNo 从 1 开始，不能删除当前正在写作的章节。"
                + "reason 必须写清楚为什么要删，会原样展示给作者。";
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
                                "description", "要删除的章节序号，从 1 开始"),
                        "reason", Map.of("type", "string",
                                "description", "删除理由，必须具体；会展示给作者作为确认依据")),
                "required", List.of("chapterNo", "reason"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Integer chapterNo = intOf(arguments.get("chapterNo"));
        if (chapterNo == null || chapterNo < 1) {
            return "错误：chapterNo 必须是 ≥1 的整数。";
        }
        String reason = text(arguments.get("reason"));
        if (!StringUtils.hasText(reason)) {
            return "错误：必须提供 reason，说明为什么要删除这一章。";
        }
        if (reason.length() > 800) {
            reason = reason.substring(0, 800);
        }

        Optional<Chapter> target = chapterIndex.byNumber(projectId, chapterNo);
        if (target.isEmpty()) {
            return "错误：本书没有第 " + chapterNo + " 章，无法删除。";
        }
        Chapter chapter = target.get();
        if (chapter.getId().equals(context.chapterId())) {
            return "错误：不能删除当前正在写作的章节。"
                    + "如果确实要删，请让作者在章节列表里手动删除，或换一章后再操作。";
        }

        ChapterDeleteRequest request = deleteRequestService.request(
                projectId, context.conversationId(), chapter, reason);

        if (context.autoApproveDelete()) {
            try {
                deleteRequestService.approveNow(request);
                log.warn("写作 Agent 按自动批准设置删除了章节 {}「{}」", chapter.getId(), chapter.getTitle());
                return "已按作者的『自动批准删除』设置删除第 " + chapterNo + " 章《"
                        + chapter.getTitle() + "》。\n"
                        + "风险提示：正文、历史版本与章节记忆已一并删除，无法恢复。"
                        + "请明确告诉作者你删除了哪一章、为什么删；如果这是误删，只能靠数据库备份恢复。";
            } catch (Exception e) {
                return "自动批准删除失败：" + e.getMessage()
                        + "。删除申请仍保留在写作面板，作者可以手动确认。";
            }
        }

        return "已创建删除申请：第 " + chapterNo + " 章《" + chapter.getTitle() + "》。\n"
                + "章节现在<b>还没有被删除</b>，等作者在写作面板的『删除申请』卡片里确认。\n"
                + "请告诉作者：你申请删除哪一章、为什么；并在确认前不要再重复提交同一章的删除申请。";
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
