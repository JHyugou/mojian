package com.mojian.agent;

import com.mojian.agent.tool.impl.SaveChapterDraftTool;
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

/** 写作 Agent 不再被前端当前章节锁死：save_chapter_draft 按 chapterNo 保存。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SaveChapterDraftToolTest {

    @Autowired
    private SaveChapterDraftTool tool;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;

    private Long project() {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName("自由保存测试");
        return projectService.create(request).getId();
    }

    private Chapter chapter(Long projectId, String title) {
        ChapterSaveRequest request = new ChapterSaveRequest();
        request.setTitle(title);
        return chapterService.create(projectId, request);
    }

    @Test
    @DisplayName("上下文没有 chapterId 时，仍可按 chapterNo 写入目标章节")
    void 按章号写入任意章节() {
        Long projectId = project();
        Chapter first = chapter(projectId, "第一章 旧稿");
        Chapter second = chapter(projectId, "第二章 目标");
        AgentContext context = AgentContext.of(projectId, AgentRole.WRITER);

        String result = tool.execute(context, Map.of(
                "chapterNo", 2,
                "content", "这是第二章的正文。",
                "note", "按写作要求重写"));

        assertThat(result).contains("第 2 章").contains("第二章 目标");
        assertThat(chapterService.requireById(second.getId()).getContent()).isEqualTo("这是第二章的正文。");
        assertThat(chapterService.requireById(first.getId()).getContent()).isNull();
    }

    @Test
    @DisplayName("缺少 chapterNo 时拒绝保存")
    void 缺少章号拒绝() {
        Long projectId = project();
        chapter(projectId, "第一章");
        String result = tool.execute(AgentContext.of(projectId, AgentRole.WRITER),
                Map.of("content", "正文"));
        assertThat(result).contains("需要 chapterNo");
    }
}
