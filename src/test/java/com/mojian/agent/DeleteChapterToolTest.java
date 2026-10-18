package com.mojian.agent;

import com.mojian.agent.entity.ChapterDeleteRequest;
import com.mojian.agent.service.ChapterDeleteRequestService;
import com.mojian.agent.tool.impl.DeleteChapterTool;
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
 * 删除章节工具：默认只申请、不删除；自动批准时才真正删；禁止删当前章。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeleteChapterToolTest {

    @Autowired
    private DeleteChapterTool tool;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;
    @Autowired
    private ChapterDeleteRequestService requestService;

    private Long createProjectWithChapters(int count) {
        ProjectSaveRequest project = new ProjectSaveRequest();
        project.setName("删章测试");
        Long projectId = projectService.create(project).getId();
        for (int i = 1; i <= count; i++) {
            ChapterSaveRequest chapter = new ChapterSaveRequest();
            chapter.setTitle("第 " + i + " 章 测试");
            chapterService.create(projectId, chapter);
        }
        return projectId;
    }

    @Test
    @DisplayName("默认只创建待确认申请，章节不会消失")
    void 默认只申请不删除() {
        Long projectId = createProjectWithChapters(2);
        List<Chapter> chapters = chapterService.list(projectId);
        Chapter current = chapters.get(1);

        String result = tool.execute(AgentContext.of(projectId, AgentRole.WRITER),
                Map.of("chapterNo", 1, "reason", "这一章与后文重复，需要删除后重排。"));

        assertThat(result).contains("已创建删除申请").contains("还没有被删除");
        List<ChapterDeleteRequest> pending = requestService.pending(projectId);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getChapterId()).isEqualTo(chapters.get(0).getId());
        assertThat(pending.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(chapterService.getById(chapters.get(0).getId())).isNotNull();
    }

    @Test
    @DisplayName("作者批准后才真正删除，申请状态变为 APPROVED")
    void 批准后删除() {
        Long projectId = createProjectWithChapters(2);
        List<Chapter> chapters = chapterService.list(projectId);
        tool.execute(AgentContext.of(projectId, AgentRole.WRITER),
                Map.of("chapterNo", 1, "reason", "重复"));
        ChapterDeleteRequest request = requestService.pending(projectId).get(0);

        requestService.approve(request.getId());

        assertThat(chapterService.getById(chapters.get(0).getId())).isNull();
        assertThat(requestService.require(request.getId()).getStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("作者开启自动批准时立即删除，但仍留 APPROVED 审计记录")
    void 自动批准立即删除() {
        Long projectId = createProjectWithChapters(2);
        List<Chapter> chapters = chapterService.list(projectId);
        // 当前章是第 2 章，删除第 1 章
        AgentContext context = AgentContext.forChapter(projectId, AgentRole.WRITER, null,
                chapters.get(1).getId(), true);

        String result = tool.execute(context, Map.of("chapterNo", 1, "reason", "重复"));

        assertThat(result).contains("已按作者的『自动批准删除』设置删除");
        assertThat(chapterService.getById(chapters.get(0).getId())).isNull();
        assertThat(requestService.recent(projectId, 10)).hasSize(1);
        assertThat(requestService.recent(projectId, 10).get(0).getStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("不能删除当前正在写的章")
    void 不能删除当前章() {
        Long projectId = createProjectWithChapters(2);
        List<Chapter> chapters = chapterService.list(projectId);
        AgentContext context = AgentContext.forChapter(projectId, AgentRole.WRITER, null,
                chapters.get(0).getId(), true);

        String result = tool.execute(context, Map.of("chapterNo", 1, "reason", "想删"));

        assertThat(result).contains("不能删除当前正在写作的章节");
        assertThat(requestService.recent(projectId, 10)).isEmpty();
        assertThat(chapterService.getById(chapters.get(0).getId())).isNotNull();
    }
}
