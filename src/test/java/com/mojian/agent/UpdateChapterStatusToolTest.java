package com.mojian.agent;

import com.mojian.agent.tool.impl.UpdateChapterStatusTool;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
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
 * 策划调整章节状态：审稿通过后可定稿；空章不能完成，非法状态拒绝。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UpdateChapterStatusToolTest {

    @Autowired
    private UpdateChapterStatusTool tool;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;

    private Long project() {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName("章节状态测试");
        return projectService.create(request).getId();
    }

    private Chapter chapter(Long projectId, String content) {
        ChapterSaveRequest request = new ChapterSaveRequest();
        request.setTitle("第一章 定稿测试");
        request.setContent(content);
        return chapterService.create(projectId, request);
    }

    @Test
    @DisplayName("审稿后把有正文的章节标记为已完成")
    void 标记完成() {
        Long projectId = project();
        Chapter chapter = chapter(projectId, "这是已经审过、可以定稿的正文。");
        AgentContext context = AgentContext.of(projectId, AgentRole.PLANNER);

        String result = tool.execute(context, Map.of("chapterNo", 1, "status", "COMPLETED",
                "reason", "审稿通过，人物与前情一致，文风达标。"));

        assertThat(result).contains("改为 已完成").contains("(COMPLETED)");
        assertThat(chapterService.requireById(chapter.getId()).getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("空章节不能标记完成；非法状态拒绝；重复设置直接说明")
    void 安全校验() {
        Long projectId = project();
        Chapter empty = chapter(projectId, "");
        AgentContext context = AgentContext.of(projectId, AgentRole.PLANNER);

        assertThat(tool.execute(context, Map.of("chapterNo", 1, "status", "COMPLETED")))
                .contains("还没有正文");
        assertThat(chapterService.requireById(empty.getId()).getStatus()).isEqualTo("DRAFT");

        assertThat(tool.execute(context, Map.of("chapterNo", 1, "status", "PUBLISHED")))
                .contains("只支持 DRAFT / WRITING / COMPLETED");

        assertThat(tool.execute(context, Map.of("chapterNo", 1, "status", "DRAFT")))
                .contains("已经是 DRAFT 状态");
    }
}
