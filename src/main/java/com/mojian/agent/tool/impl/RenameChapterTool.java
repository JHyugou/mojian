package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.material.ChapterIndex;
import com.mojian.agent.tool.AgentTool;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 策划修改章节标题，并同步修改关联的 CHAPTER 蓝图节点名称。
 *
 * <p>章节标题和蓝图节点名是两个字段：只改一个会出现“蓝图叫旧名、章节叫新名”的错位。
 * 这个工具默认把两边一起改；只有明确 syncBlueprint=false 时才只改章节。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RenameChapterTool implements AgentTool {

    private final ChapterIndex chapterIndex;
    private final ChapterService chapterService;
    private final OutlineService outlineService;

    @Override
    public String name() {
        return "rename_chapter";
    }

    @Override
    public String description() {
        return "修改某一章的标题，并默认同步修改它关联的 CHAPTER 蓝图节点名称。"
                + "适合用户说“把第六章改名成……”“章名改成……”这类需求。"
                + "chapterNo 从 1 开始；title 是新的章节标题。"
                + "会直接修改章节与蓝图名称，不生成草稿；只动标题，不碰正文、状态、版本。";
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
                        "title", Map.of("type", "string",
                                "description", "新的章节标题（建议保留“第 N 章 ”前缀）"),
                        "syncBlueprint", Map.of("type", "boolean",
                                "description", "是否同步修改关联的 CHAPTER 蓝图节点名，默认 true"),
                        "reason", Map.of("type", "string",
                                "description", "改名理由或用户原话，可选")),
                "required", List.of("chapterNo", "title"));
    }

    @Override
    @Transactional
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Integer chapterNo = intOf(arguments.get("chapterNo"));
        String title = text(arguments.get("title"));
        boolean syncBlueprint = boolOf(arguments.get("syncBlueprint"), true);
        String reason = text(arguments.get("reason"));

        if (chapterNo == null || chapterNo < 1) {
            return "错误：chapterNo 必须是 ≥ 1 的整数。";
        }
        if (!StringUtils.hasText(title)) {
            return "错误：必须提供新的章节标题 title。";
        }
        if (title.length() > 200) {
            return "错误：章节标题不能超过 200 字。";
        }

        Optional<Chapter> optional = chapterIndex.byNumber(projectId, chapterNo);
        if (optional.isEmpty()) {
            return "错误：本书没有第 " + chapterNo + " 章。";
        }
        Chapter chapter = optional.get();
        String oldTitle = chapter.getTitle();
        if (title.equals(oldTitle)) {
            return "第 " + chapterNo + " 章标题已经是「" + title + "」，无需修改。";
        }

        chapterService.rename(chapter.getId(), title);

        boolean blueprintRenamed = false;
        if (syncBlueprint && chapter.getOutlineNodeId() != null) {
            PlotOutline node = outlineService.requireById(chapter.getOutlineNodeId());
            if (projectId.equals(node.getProjectId()) && "CHAPTER".equals(node.getNodeType())) {
                outlineService.rename(node.getId(), title);
                blueprintRenamed = true;
            }
        }

        log.info("策划把第 {} 章标题「{}」改为「{}」，蓝图同步：{}（理由：{}）",
                chapterNo, oldTitle, title, blueprintRenamed, reason);
        return "已把第 " + chapterNo + " 章标题从「" + oldTitle + "」改为「" + title + "」。"
                + (blueprintRenamed
                    ? "关联的蓝图节点名称也已同步修改。"
                    : (syncBlueprint ? "这一章没有关联的 CHAPTER 蓝图节点，只改了章节标题。" : "未同步蓝图节点。"))
                + "\n请告诉用户改了什么；不要再调用其他工具。";
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

    private boolean boolOf(Object value, boolean fallback) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value == null) {
            return fallback;
        }
        String s = String.valueOf(value).trim();
        if ("false".equalsIgnoreCase(s) || "0".equals(s)) return false;
        if ("true".equalsIgnoreCase(s) || "1".equals(s)) return true;
        return fallback;
    }

    private String text(Object value) {
        return value == null || !StringUtils.hasText(String.valueOf(value))
                ? "" : String.valueOf(value).trim();
    }
}
