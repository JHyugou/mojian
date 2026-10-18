package com.mojian.agent;

import com.mojian.agent.entity.ChapterSupplement;
import com.mojian.agent.service.ChapterSupplementService;
import com.mojian.agent.service.WriterService;
import com.mojian.agent.tool.impl.AddChapterSupplementTool;
import com.mojian.agent.tool.impl.ReviewChapterTool;
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
 * 策划审稿 → 补充要求 → 注入写作上下文。
 *
 * <p>守两件事：补充要求与写作要求分开、多条补充要求互不合并；
 * 写作任务确实会把它们作为必备材料注入。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChapterSupplementTest {

    @Autowired
    private ReviewChapterTool reviewTool;
    @Autowired
    private AddChapterSupplementTool supplementTool;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;
    @Autowired
    private ChapterSupplementService supplementService;
    @Autowired
    private WriterService writerService;

    private Chapter createChapter(String content, String writingRequirement) {
        ProjectSaveRequest project = new ProjectSaveRequest();
        project.setName("审稿测试项目");
        Long projectId = projectService.create(project).getId();
        ChapterSaveRequest request = new ChapterSaveRequest();
        request.setTitle("第一章 试稿");
        request.setContent(content);
        Chapter chapter = chapterService.create(projectId, request);
        if (writingRequirement != null) {
            chapterService.updateWritingRequirement(chapter.getId(), writingRequirement);
        }
        return chapterService.requireById(chapter.getId());
    }

    @Test
    @DisplayName("审稿返回正文、写作要求；补充要求逐条独立并注入写作上下文")
    void 审稿补充要求与上下文注入() {
        Chapter chapter = createChapter("官道上没有活人。他走了二十里，看见一辆断轴的车。",
                "第三人称，锁主角视角；短句。");
        AgentContext context = AgentContext.of(chapter.getProjectId(), AgentRole.PLANNER);

        String review = reviewTool.execute(context, Map.of("chapterNo", 1));
        assertThat(review).contains("官道上没有活人").contains("第三人称，锁主角视角");

        String first = supplementTool.execute(context,
                Map.of("chapterNo", 1, "supplement", "把断轴车的描写改成听觉先行：先听见车轴断裂声。",
                        "reason", "正文开头的视觉信息太直接"));
        String second = supplementTool.execute(context,
                Map.of("chapterNo", 1, "supplement", "结尾补一句他回头看了一眼来路。",
                        "reason", "缺少对前情的呼应"));
        assertThat(first).contains("已给第 1 章").contains("补充要求");
        assertThat(second).contains("已给第 1 章");

        List<ChapterSupplement> pending = supplementService.pending(chapter.getId());
        assertThat(pending).hasSize(2);
        assertThat(pending.get(0).getId()).isNotEqualTo(pending.get(1).getId());
        assertThat(pending).extracting(ChapterSupplement::getContent)
                .anySatisfy(text -> assertThat(text).contains("听觉先行"))
                .anySatisfy(text -> assertThat(text).contains("回头看了一眼来路"));

        // 重复同一条不重复排队，但两条不同意见必须分开
        String duplicate = supplementTool.execute(context,
                Map.of("chapterNo", 1, "supplement", "把断轴车的描写改成听觉先行：先听见车轴断裂声。"));
        assertThat(duplicate).contains("已经待执行");
        assertThat(supplementService.pending(chapter.getId())).hasSize(2);

        // 写作上下文：补充要求与写作要求同时存在，但编号独立
        String contextText = writerService.contextPreview(chapter.getId()).text();
        assertThat(contextText).contains("本章写作要求").contains("第三人称，锁主角视角");
        assertThat(contextText).contains("本章补充要求 1").contains("本章补充要求 2");
        assertThat(contextText).contains("听觉先行").contains("回头看了一眼来路");

        // 审稿能看到两条待执行
        assertThat(reviewTool.execute(context, Map.of("chapterNo", 1)))
                .contains("已有补充要求").contains("待执行");

        // 写作成功后由 WriterService 标记；这里直接验证标记后审稿显示已应用
        supplementService.markApplied(supplementService.pending(chapter.getId()));
        assertThat(reviewTool.execute(context, Map.of("chapterNo", 1))).contains("已应用");
    }

    @Test
    @DisplayName("没有正文时不审稿；没有章节时拒绝追加补充要求")
    void 空章节与不存在章节() {
        Chapter chapter = createChapter("", null);
        AgentContext context = AgentContext.of(chapter.getProjectId(), AgentRole.PLANNER);
        assertThat(reviewTool.execute(context, Map.of("chapterNo", 1))).contains("还没有正文");
        assertThat(supplementTool.execute(context, Map.of("chapterNo", 2, "supplement", "补一句")))
                .contains("没有第 2 章");
    }
}
