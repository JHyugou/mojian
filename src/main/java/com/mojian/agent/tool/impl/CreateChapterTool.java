package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.tool.AgentTool;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 策划 Agent 新建章节。
 *
 * <p>没有这个工具时，策划把蓝图做好了却无法落成章节，只能让作者切到写作模式手动建，
 * 然后再回来下发写作指令 —— 流程断成两截。这里让它一次完成：
 * 建章节、关联蓝图、顺手写下本章写作要求。
 *
 * <p>建章是低风险动作（可删、可改），不需要像删除那样二次确认；
 * 但它仍受限制：一次只建一章、标题不能重复、蓝图节点必须属于当前项目。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateChapterTool implements AgentTool {

    private final ChapterService chapterService;
    private final OutlineService outlineService;

    @Override
    public String name() {
        return "create_chapter";
    }

    @Override
    public String description() {
        return "新建一个章节（空正文，状态为草稿），可以关联本章蓝图并顺手写本章写作要求。"
                + "title 必填；outlineNodeId 选填，必须是当前项目里 CHAPTER 类型的蓝图节点（先用 query_outline 查）。"
                + "writingRequirement 选填，写这一章『怎么写』（人称、视角、篇幅、禁忌、必须出现的元素）。"
                + "一次只建一章；标题重复会被拒绝。"
                + "建完后告诉作者章节已创建，并问是否继续下发写作指令或开始写。";
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
                        "title", Map.of("type", "string",
                                "description", "章节标题，例如『第一章 卸甲』"),
                        "outlineNodeId", Map.of("type", "integer",
                                "description", "可选：关联的蓝图节点记录 ID（CHAPTER 类型，query_outline 查）"),
                        "writingRequirement", Map.of("type", "string",
                                "description", "可选：本章写作要求，会作为必备材料交给写作 Agent")),
                "required", List.of("title"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        String title = text(arguments.get("title"));
        if (!StringUtils.hasText(title)) {
            return "错误：title 不能为空。";
        }
        if (title.length() > 200) {
            return "错误：章节标题太长（上限 200 字）。";
        }

        List<Chapter> existing = chapterService.list(projectId);
        for (Chapter chapter : existing) {
            if (title.trim().equalsIgnoreCase(chapter.getTitle() == null ? "" : chapter.getTitle().trim())) {
                return "错误：本书已有同名章节《" + chapter.getTitle() + "》（记录 ID " + chapter.getId()
                        + "），不要重复创建。";
            }
        }

        Long outlineNodeId = longOf(arguments.get("outlineNodeId"));
        String blueprintName = null;
        if (outlineNodeId != null) {
            PlotOutline node = outlineService.requireById(outlineNodeId);
            if (!projectId.equals(node.getProjectId())) {
                return "错误：蓝图节点 " + outlineNodeId + " 不属于当前项目，不能关联。";
            }
            if (!"CHAPTER".equals(node.getNodeType())) {
                return "错误：节点「" + node.getName() + "」是 " + node.getNodeType()
                        + " 类型，只有 CHAPTER 类型的蓝图节点能关联到章节。";
            }
            blueprintName = node.getName();
        }

        ChapterSaveRequest request = new ChapterSaveRequest();
        request.setTitle(title.trim());
        request.setOutlineNodeId(outlineNodeId);
        request.setStatus("DRAFT");
        Chapter chapter = chapterService.create(projectId, request);

        String requirement = text(arguments.get("writingRequirement"));
        if (StringUtils.hasText(requirement)) {
            chapterService.updateWritingRequirement(chapter.getId(), requirement);
        }

        int chapterNo = existing.size() + 1;
        log.info("策划 Agent 创建章节 {}「{}」（项目 {}，蓝图 {}）", chapter.getId(), chapter.getTitle(),
                projectId, outlineNodeId);
        StringBuilder result = new StringBuilder("已创建第 " + chapterNo + " 章《" + chapter.getTitle()
                + "》（记录 ID " + chapter.getId() + "）。\n");
        result.append(blueprintName != null
                ? "已关联蓝图节点「" + blueprintName + "」。\n"
                : "未关联蓝图节点；写作时它会按写作要求与作者指令写，建议确认是否要关联蓝图。\n");
        result.append(StringUtils.hasText(requirement)
                ? "本章写作要求已保存，会作为必备材料交给写作 Agent。\n"
                : "本章还没有写作要求；如果这一章有明确的写法约束，建议补上。\n");
        result.append("请告诉作者章节已创建，并问是否继续（下发写作指令 / 切到写作模式开写）。");
        return result.toString();
    }

    private Long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
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
