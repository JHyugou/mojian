package com.mojian.agent;

import com.mojian.agent.entity.AgentDraft;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.service.ConversationService;
import com.mojian.agent.service.DraftService;
import com.mojian.agent.tool.impl.RenameChapterTool;
import com.mojian.agent.tool.impl.UpdateBlueprintTool;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import com.mojian.outline.dto.OutlineNodeSaveRequest;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章节/蓝图改名：章节标题和关联 CHAPTER 节点要能一起改，蓝图也能单独改名。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RenameChapterToolTest {

    @Autowired
    private RenameChapterTool renameChapterTool;
    @Autowired
    private UpdateBlueprintTool updateBlueprintTool;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;
    @Autowired
    private OutlineService outlineService;
    @Autowired
    private ConversationService conversationService;
    @Autowired
    private DraftService draftService;

    private Long project(String name) {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName(name);
        return projectService.create(request).getId();
    }

    private PlotOutline blueprint(Long projectId, String name) {
        OutlineNodeSaveRequest request = new OutlineNodeSaveRequest();
        request.setNodeType("CHAPTER");
        request.setName(name);
        request.setSummary("蓝图摘要");
        return outlineService.create(projectId, request);
    }

    private Chapter chapter(Long projectId, String title, Long outlineNodeId) {
        ChapterSaveRequest request = new ChapterSaveRequest();
        request.setTitle(title);
        request.setOutlineNodeId(outlineNodeId);
        request.setStatus("DRAFT");
        return chapterService.create(projectId, request);
    }

    @Test
    @DisplayName("改章名时同步改关联蓝图节点名；syncBlueprint=false 时只改章节")
    void 章节改名同步蓝图() {
        Long projectId = project("改名测试");
        PlotOutline node = blueprint(projectId, "第六章 不留手");
        Chapter chapter = chapter(projectId, "第六章 不留手", node.getId());
        AgentContext context = AgentContext.of(projectId, AgentRole.PLANNER);

        String result = renameChapterTool.execute(context,
                Map.of("chapterNo", 1, "title", "第六章 决赛"));
        assertThat(result).contains("第六章 决赛").contains("蓝图节点名称也已同步修改");
        assertThat(chapterService.requireById(chapter.getId()).getTitle()).isEqualTo("第六章 决赛");
        assertThat(outlineService.requireById(node.getId()).getName()).isEqualTo("第六章 决赛");

        String noSync = renameChapterTool.execute(context,
                Map.of("chapterNo", 1, "title", "第六章 决战", "syncBlueprint", false));
        assertThat(noSync).contains("未同步蓝图节点");
        assertThat(outlineService.requireById(node.getId()).getName()).isEqualTo("第六章 决赛");
    }

    @Test
    @DisplayName("蓝图支持只改名：生成修改草稿，确认后节点名更新且摘要保留")
    void 蓝图单独改名() {
        Long projectId = project("蓝图改名测试");
        PlotOutline node = blueprint(projectId, "第六章 不留手");
        AgentConversation conversation = conversationService.create(projectId, AgentRole.PLANNER, "改蓝图名");
        AgentContext context = AgentContext.ofConversation(projectId, AgentRole.PLANNER, conversation.getId());

        String result = updateBlueprintTool.execute(context,
                Map.of("nodeId", node.getId(), "name", "第六章 决赛", "reason", "决赛比不留手准确"));
        assertThat(result).contains("改名为「第六章 决赛」").contains("尚未生效");

        AgentDraft draft = draftService.listPendingByProject(projectId).get(0);
        assertThat(draft.getTitle()).isEqualTo("第六章 决赛");
        draftService.accept(draft.getId());

        PlotOutline renamed = outlineService.requireById(node.getId());
        assertThat(renamed.getName()).isEqualTo("第六章 决赛");
        assertThat(renamed.getSummary()).isEqualTo("蓝图摘要");
    }
}
