package com.mojian.agent;

import com.mojian.agent.entity.ChapterSupplement;
import com.mojian.agent.service.ChapterSupplementService;
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

import java.util.List;
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
    @Autowired
    private ChapterSupplementService supplementService;

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
    @DisplayName("还有待核验补充要求时不能标记完成；确认落实后可以")
    void 未核验补充要求阻止完成() {
        Long projectId = project();
        Chapter chapter = chapter(projectId, "正文已经写完，但还有补充要求待核验。");
        ChapterSupplement supplement = supplementService.create(projectId, chapter.getId(),
                "结尾再冷一点", "审稿意见");
        supplementService.markSubmitted(List.of(supplement));
        AgentContext context = AgentContext.of(projectId, AgentRole.PLANNER);

        String blocked = tool.execute(context, Map.of("chapterNo", 1, "status", "COMPLETED"));
        assertThat(blocked).contains("补充要求没有完成核验").contains("待核验 1");
        assertThat(chapterService.requireById(chapter.getId()).getStatus()).isNotEqualTo("COMPLETED");

        supplementService.verify(chapter.getId(), supplement.getId());
        String passed = tool.execute(context, Map.of("chapterNo", 1, "status", "COMPLETED"));
        assertThat(passed).contains("改为 已完成");
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
