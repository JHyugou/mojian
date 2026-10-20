package com.mojian.outline;

import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.service.ChapterService;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.outline.dto.OutlineNodeSaveRequest;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import com.mojian.outline.vo.OutlineNodeVO;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OutlineServiceTest {

    @Autowired
    private OutlineService outlineService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;

    private Long newProjectId() {
        ProjectSaveRequest req = new ProjectSaveRequest();
        req.setName("蓝图测试项目");
        return projectService.create(req).getId();
    }

    private PlotOutline newNode(Long projectId, String type, String name, Long parentId) {
        OutlineNodeSaveRequest req = new OutlineNodeSaveRequest();
        req.setNodeType(type);
        req.setName(name);
        req.setParentId(parentId);
        return outlineService.create(projectId, req);
    }

    private PlotOutline newNodeWithSort(Long projectId, String name, int sortOrder) {
        OutlineNodeSaveRequest req = new OutlineNodeSaveRequest();
        req.setNodeType("CHAPTER");
        req.setName(name);
        req.setSortOrder(sortOrder);
        return outlineService.create(projectId, req);
    }

    @Test
    void treeOrdersRootsByDatabaseCreationTimeNotStaleSortOrder() {
        Long projectId = newProjectId();
        PlotOutline first = newNodeWithSort(projectId, "先创建但 sortOrder 很大", 99);
        PlotOutline second = newNodeWithSort(projectId, "后创建但 sortOrder 很小", -99);

        List<OutlineNodeVO> tree = outlineService.tree(projectId);

        assertEquals(2, tree.size());
        assertEquals(first.getName(), tree.get(0).getName());
        assertEquals(second.getName(), tree.get(1).getName());
    }

    @Test
    void treeAssemblesParentChildNesting() {
        Long projectId = newProjectId();
        PlotOutline volume = newNode(projectId, "VOLUME", "第一卷", null);
        PlotOutline arc = newNode(projectId, "ARC", "初入江湖", volume.getId());
        newNode(projectId, "CHAPTER", "夜雨客栈", arc.getId());

        List<OutlineNodeVO> tree = outlineService.tree(projectId);

        assertEquals(1, tree.size());
        assertEquals("第一卷", tree.get(0).getName());
        assertEquals(1, tree.get(0).getChildren().size());
        assertEquals("初入江湖", tree.get(0).getChildren().get(0).getName());
        assertEquals(1, tree.get(0).getChildren().get(0).getChildren().size());
        assertEquals("夜雨客栈", tree.get(0).getChildren().get(0).getChildren().get(0).getName());
    }

    @Test
    void deleteSubtreeClearsChapterLinkButKeepsChapter() {
        Long projectId = newProjectId();
        PlotOutline volume = newNode(projectId, "VOLUME", "第一卷", null);
        PlotOutline chapterNode = newNode(projectId, "CHAPTER", "夜雨客栈", volume.getId());

        ChapterSaveRequest chapterReq = new ChapterSaveRequest();
        chapterReq.setTitle("第一章 夜雨");
        chapterReq.setOutlineNodeId(chapterNode.getId());
        Long chapterId = chapterService.create(projectId, chapterReq).getId();

        outlineService.delete(volume.getId());

        assertNotNull(chapterService.requireById(chapterId));
        assertNull(chapterService.requireById(chapterId).getOutlineNodeId());
        assertEquals(0, outlineService.tree(projectId).size());
    }

    @Test
    void updateRejectsSelfParent() {
        Long projectId = newProjectId();
        PlotOutline volume = newNode(projectId, "VOLUME", "第一卷", null);

        OutlineNodeSaveRequest req = new OutlineNodeSaveRequest();
        req.setNodeType("VOLUME");
        req.setName("第一卷");
        req.setParentId(volume.getId());

        BusinessException e = assertThrows(BusinessException.class,
                () -> outlineService.update(volume.getId(), req));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getErrorCode().getCode());
    }

    @Test
    void createRejectsParentFromOtherProject() {
        Long projectIdA = newProjectId();
        Long projectIdB = newProjectId();
        PlotOutline volumeInB = newNode(projectIdB, "VOLUME", "B的卷", null);

        OutlineNodeSaveRequest req = new OutlineNodeSaveRequest();
        req.setNodeType("ARC");
        req.setName("A的篇");
        req.setParentId(volumeInB.getId());

        BusinessException e = assertThrows(BusinessException.class,
                () -> outlineService.create(projectIdA, req));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getErrorCode().getCode());
    }
}
