package com.mojian.agent;

import com.mojian.agent.tool.impl.CreateChapterTool;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 策划创建章节：能关联蓝图、能写写作要求；跨项目蓝图与重复标题必须拒绝。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CreateChapterToolTest {

    @Autowired
    private CreateChapterTool tool;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;
    @Autowired
    private OutlineService outlineService;

    private Long project(String name) {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName(name);
        return projectService.create(request).getId();
    }

    private PlotOutline blueprint(Long projectId, String name, String type) {
        OutlineNodeSaveRequest request = new OutlineNodeSaveRequest();
        request.setNodeType(type);
        request.setName(name);
        request.setSummary("摘要");
        return outlineService.create(projectId, request);
    }

    @Test
    @DisplayName("创建章节：关联蓝图、保存写作要求")
    void 创建并关联蓝图() {
        Long projectId = project("建章测试");
        PlotOutline node = blueprint(projectId, "第一章 卸甲", "CHAPTER");

        String result = tool.execute(AgentContext.of(projectId, AgentRole.PLANNER),
                Map.of("title", "第一章 卸甲", "outlineNodeId", node.getId(),
                        "writingRequirement", "第三人称，锁四皇子视角；开篇三句内出现卸甲。"));

        assertThat(result).contains("已创建第 1 章").contains("已关联蓝图节点");
        List<Chapter> chapters = chapterService.list(projectId);
        assertThat(chapters).hasSize(1);
        Chapter chapter = chapters.get(0);
        assertThat(chapter.getTitle()).isEqualTo("第一章 卸甲");
        assertThat(chapter.getOutlineNodeId()).isEqualTo(node.getId());
        assertThat(chapter.getWritingRequirement()).contains("锁四皇子视角");
        assertThat(chapter.getStatus()).isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("重复标题拒绝；跨项目蓝图拒绝；非 CHAPTER 蓝图拒绝")
    void 安全校验() {
        Long projectA = project("项目 A");
        Long projectB = project("项目 B");
        PlotOutline otherNode = blueprint(projectB, "别家蓝图", "CHAPTER");
        PlotOutline arc = blueprint(projectA, "第一卷", "VOLUME");
        AgentContext contextA = AgentContext.of(projectA, AgentRole.PLANNER);

        String wrongProject = tool.execute(contextA,
                Map.of("title", "第一章", "outlineNodeId", otherNode.getId()));
        assertThat(wrongProject).contains("不属于当前项目");

        String wrongType = tool.execute(contextA,
                Map.of("title", "第一章", "outlineNodeId", arc.getId()));
        assertThat(wrongType).contains("只有 CHAPTER 类型");

        tool.execute(contextA, Map.of("title", "第一章 开场"));
        String duplicate = tool.execute(contextA, Map.of("title", "第一章 开场"));
        assertThat(duplicate).contains("同名章节");
        assertThat(chapterService.list(projectA)).hasSize(1);
    }
}
