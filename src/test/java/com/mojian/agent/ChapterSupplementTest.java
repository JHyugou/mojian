package com.mojian.agent;

import com.mojian.agent.entity.ChapterSupplement;
import com.mojian.agent.service.ChapterSupplementService;
import com.mojian.agent.service.WriterService;
import com.mojian.agent.tool.impl.AddChapterSupplementTool;
import com.mojian.agent.tool.impl.RemoveChapterSupplementTool;
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
    private RemoveChapterSupplementTool removeTool;
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
        assertThat(contextText).contains("第 1 章补充要求 1").contains("第 1 章补充要求 2");
        assertThat(contextText).contains("听觉先行").contains("回头看了一眼来路");

        // 审稿能看到两条待执行
        assertThat(reviewTool.execute(context, Map.of("chapterNo", 1)))
                .contains("已有补充要求").contains("待执行");

        // 写作成功后由 WriterService 标记；这里直接验证标记后审稿显示已核验
        supplementService.markApplied(supplementService.pending(chapter.getId()));
        assertThat(reviewTool.execute(context, Map.of("chapterNo", 1)))
                .contains("已核验").contains("已核验 2");
    }

    @Test
    @DisplayName("用户不要某条补充要求时，策划可按记录 ID 移除；只删这一条")
    void 移除单条补充要求() {
        Chapter chapter = createChapter("第一稿正文。", null);
        AgentContext context = AgentContext.of(chapter.getProjectId(), AgentRole.PLANNER);
        supplementTool.execute(context, Map.of("chapterNo", 1, "supplement", "第一条：改成短句。"));
        supplementTool.execute(context, Map.of("chapterNo", 1, "supplement", "第二条：补一个细节。"));
        List<ChapterSupplement> pending = supplementService.pending(chapter.getId());
        assertThat(pending).hasSize(2);
        ChapterSupplement first = pending.get(0);
        ChapterSupplement second = pending.get(1);

        String review = reviewTool.execute(context, Map.of("chapterNo", 1));
        assertThat(review).contains("记录 ID " + first.getId()).contains("记录 ID " + second.getId());

        String removed = removeTool.execute(context, Map.of("supplementId", first.getId()));
        assertThat(removed).contains("已移除补充要求").contains("第一条");
        assertThat(supplementService.pending(chapter.getId())).hasSize(1);
        assertThat(supplementService.pending(chapter.getId()).get(0).getId()).isEqualTo(second.getId());

        String contextText = writerService.contextPreview(chapter.getId()).text();
        assertThat(contextText).doesNotContain("第一条：改成短句").contains("第二条：补一个细节");
        assertThat(reviewTool.execute(context, Map.of("chapterNo", 1)))
                .doesNotContain("第一条：改成短句").contains("第二条：补一个细节");
    }

    @Test
    @DisplayName("单独执行状态：EXECUTING 时不进入普通写作上下文，失败回滚后重新可执行")
    void 执行状态流转() {
        Chapter chapter = createChapter("已有正文。", null);
        AgentContext context = AgentContext.of(chapter.getProjectId(), AgentRole.PLANNER);
        supplementTool.execute(context, Map.of("chapterNo", 1, "supplement", "执行态测试：改结尾。"));
        ChapterSupplement supplement = supplementService.pending(chapter.getId()).get(0);

        supplementService.markExecuting(supplement.getId(), 123456789L);
        ChapterSupplement executing = supplementService.require(supplement.getId());
        assertThat(executing.getStatus()).isEqualTo("EXECUTING");
        assertThat(executing.getExecutingTaskId()).isEqualTo(123456789L);
        // 审稿必须能看出这一条还在执行，而不是误标成已执行
        String executingReview = reviewTool.execute(context, Map.of("chapterNo", 1));
        assertThat(executingReview).contains("执行中 1").contains("[执行中]");
        // 普通写作（不指定 supplementId）不会重复注入正在单独执行的那条
        assertThat(writerService.contextPreview(chapter.getId()).text()).doesNotContain("执行态测试：改结尾");

        supplementService.resetExecuting(supplement.getId());
        assertThat(supplementService.require(supplement.getId()).getStatus()).isEqualTo("PENDING");
        assertThat(writerService.contextPreview(chapter.getId()).text()).contains("执行态测试：改结尾");
    }

    @Test
    @DisplayName("写完后是待核验，不是已应用；确认落实才 APPLIED，打回则回到待执行")
    void 提交待核验与确认() {
        Chapter chapter = createChapter("已有正文。", null);
        AgentContext context = AgentContext.of(chapter.getProjectId(), AgentRole.PLANNER);
        supplementTool.execute(context, Map.of("chapterNo", 1, "supplement", "核验测试：改结尾。"));
        ChapterSupplement supplement = supplementService.pending(chapter.getId()).get(0);

        supplementService.markSubmitted(List.of(supplement));
        ChapterSupplement submitted = supplementService.require(supplement.getId());
        assertThat(submitted.getStatus()).isEqualTo("SUBMITTED");
        assertThat(reviewTool.execute(context, Map.of("chapterNo", 1)))
                .contains("待核验 1").contains("[待核验]");

        supplementService.verify(chapter.getId(), supplement.getId());
        assertThat(supplementService.require(supplement.getId()).getStatus()).isEqualTo("APPLIED");
        assertThat(reviewTool.execute(context, Map.of("chapterNo", 1)))
                .contains("已核验 1").contains("[已核验]");

        supplementTool.execute(context, Map.of("chapterNo", 1, "supplement", "核验测试：再改一处。"));
        ChapterSupplement second = supplementService.pending(chapter.getId()).get(0);
        supplementService.markSubmitted(List.of(second));
        supplementService.returnToPending(chapter.getId(), second.getId());
        assertThat(supplementService.require(second.getId()).getStatus()).isEqualTo("PENDING");
    }
}
