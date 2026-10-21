package com.mojian.agent;

import com.mojian.agent.tool.impl.PatchChapterParagraphsTool;
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

/** 段落级补丁：校验版本与原文，只改指定段。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PatchChapterParagraphsToolTest {

    @Autowired
    private PatchChapterParagraphsTool tool;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;

    private Chapter chapter() {
        ProjectSaveRequest project = new ProjectSaveRequest();
        project.setName("段落补丁测试");
        Long projectId = projectService.create(project).getId();
        ChapterSaveRequest request = new ChapterSaveRequest();
        request.setTitle("第一章 补丁");
        request.setContent("第一段。\n\n第二段原文。\n\n第三段。");
        return chapterService.create(projectId, request);
    }

    @Test
    @DisplayName("按版本和原文校验后只替换目标段")
    void 替换目标段() {
        Chapter chapter = chapter();
        String result = tool.execute(AgentContext.of(chapter.getProjectId(), AgentRole.WRITER),
                Map.of("chapterNo", 1, "baseVersionNo", 0,
                        "edits", List.of(Map.of(
                                "paragraphIndex", 2,
                                "expectedText", "第二段原文。",
                                "newText", "第二段改好了。"))));

        assertThat(result).contains("新版本号 1").contains("1 个段落");
        Chapter saved = chapterService.requireById(chapter.getId());
        assertThat(saved.getContent()).contains("第二段改好了。").doesNotContain("第二段原文。");
        assertThat(saved.getCurrentVersionNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("旧版本引用拒绝修改")
    void 旧版本拒绝() {
        Chapter chapter = chapter();
        String result = tool.execute(AgentContext.of(chapter.getProjectId(), AgentRole.WRITER),
                Map.of("chapterNo", 1, "baseVersionNo", 99,
                        "edits", List.of(Map.of(
                                "paragraphIndex", 2,
                                "expectedText", "第二段原文。",
                                "newText", "不应写入"))));

        assertThat(result).contains("不是引用时的版本");
        assertThat(chapterService.requireById(chapter.getId()).getContent()).contains("第二段原文。");
    }

    @Test
    @DisplayName("段落原文不一致时拒绝")
    void 原文不一致拒绝() {
        Chapter chapter = chapter();
        String result = tool.execute(AgentContext.of(chapter.getProjectId(), AgentRole.WRITER),
                Map.of("chapterNo", 1, "baseVersionNo", 0,
                        "edits", List.of(Map.of(
                                "paragraphIndex", 2,
                                "expectedText", "错的原文",
                                "newText", "不应写入"))));

        assertThat(result).contains("原文已经变化");
        assertThat(chapterService.requireById(chapter.getId()).getContent()).contains("第二段原文。");
    }
}
