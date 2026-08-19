package com.mojian.generation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import com.mojian.generation.dto.GenerationTaskRequest;
import com.mojian.generation.dto.PlanGenerationRequest;
import com.mojian.generation.entity.GenerationTask;
import com.mojian.generation.llm.LlmClient;
import com.mojian.generation.llm.MockLlmClient;
import com.mojian.generation.mapper.GenerationTaskMapper;
import com.mojian.generation.service.GenerationTaskService;
import com.mojian.generation.vo.GenerationTaskVO;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.version.entity.ChapterVersion;
import com.mojian.version.mapper.ChapterVersionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 生成链路集成测试（SRS 13.2）：MockLlmClient + 同步执行器，
 * 覆盖两阶段流程、版本落库、失败保护、防重复、僵尸回收。
 */
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@ActiveProfiles("test")
@Transactional
class GenerationFlowTest {

    @Autowired
    private GenerationTaskService taskService;
    @Autowired
    private GenerationTaskMapper taskMapper;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;
    @Autowired
    private ChapterVersionMapper versionMapper;
    @Autowired
    private LlmClient llmClient;

    /** 测试环境把异步执行器替换为同步直执行，断言无需等待 */
    @TestConfiguration
    static class SyncExecutorConfig {
        @Bean("generationExecutor")
        @Primary
        public java.util.concurrent.Executor syncExecutor() {
            return Runnable::run;
        }
    }

    private Long newChapterId() {
        ProjectSaveRequest projectReq = new ProjectSaveRequest();
        projectReq.setName("生成测试作品");
        Long projectId = projectService.create(projectReq).getId();
        ChapterSaveRequest req = new ChapterSaveRequest();
        req.setTitle("第一章 夜雨客栈");
        return chapterService.create(projectId, req).getId();
    }

    @Test
    void planThenContentTwoStageFlow() {
        Long chapterId = newChapterId();

        // 第一阶段：剧情方案
        PlanGenerationRequest planReq = new PlanGenerationRequest();
        planReq.setInstruction("主角初到客栈，遭遇第一次刺杀");
        GenerationTaskVO plan = taskService.createPlanTask(chapterId, planReq.getInstruction());

        assertEquals("SUCCEEDED", plan.getStatus());
        assertNotNull(plan.getResult());
        assertTrue(plan.getResult().contains("剧情方案"));
        assertNotNull(plan.getContext(), "上下文快照应被记录（FR-011）");

        // 第二阶段：确认方案后生成正文
        GenerationTaskRequest contentReq = new GenerationTaskRequest();
        contentReq.setTaskType("CONTENT");
        contentReq.setPlan(plan.getResult());
        contentReq.setInstruction("按方案写正文");
        GenerationTaskVO content = taskService.createTask(chapterId, contentReq);

        assertEquals("SUCCEEDED", content.getStatus());

        Chapter chapter = chapterService.requireById(chapterId);
        assertNotNull(chapter.getContent(), "正文应写回章节");
        assertTrue(chapter.getContent().contains("夜雨客栈"));
        assertEquals(1, chapter.getCurrentVersionNo());

        List<ChapterVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<ChapterVersion>().eq(ChapterVersion::getChapterId, chapterId));
        assertEquals(1, versions.size());
        assertEquals("AI", versions.get(0).getSource());
        assertNotNull(versions.get(0).getSourceTaskId(), "版本应记录来源任务");
    }

    @Test
    void contentFailureDoesNotTouchChapter() {
        Long chapterId = newChapterId();
        ChapterSaveRequest save = new ChapterSaveRequest();
        save.setTitle("第一章 夜雨客栈");
        save.setContent("已有的正文，不能被破坏");
        chapterService.update(chapterId, save);

        ((MockLlmClient) llmClient).setFailNext(1);
        GenerationTaskRequest req = new GenerationTaskRequest();
        req.setTaskType("CONTENT");
        req.setPlan("任意方案");
        GenerationTaskVO task = taskService.createTask(chapterId, req);

        assertEquals("FAILED", task.getStatus());
        assertNotNull(task.getErrorMessage());
        assertEquals("已有的正文，不能被破坏", chapterService.requireById(chapterId).getContent());
        assertEquals(0, versionMapper.selectCount(
                new LambdaQueryWrapper<ChapterVersion>().eq(ChapterVersion::getChapterId, chapterId)));
    }

    @Test
    void failedTaskCanBeRetried() {
        Long chapterId = newChapterId();
        ((MockLlmClient) llmClient).setFailNext(1);

        PlanGenerationRequest planReq = new PlanGenerationRequest();
        planReq.setInstruction("测试重试");
        GenerationTaskVO failed = taskService.createPlanTask(chapterId, planReq.getInstruction());
        assertEquals("FAILED", failed.getStatus());

        GenerationTaskVO retried = taskService.retry(failed.getId());
        assertEquals("SUCCEEDED", retried.getStatus(), "重试应复用同一任务并成功");
        assertEquals(failed.getId(), retried.getId());
    }

    @Test
    void duplicateActiveTaskReturnsExisting() {
        Long chapterId = newChapterId();

        GenerationTask running = new GenerationTask();
        running.setChapterId(chapterId);
        running.setTaskType("PLOT_PLAN");
        running.setStatus("RUNNING");
        taskMapper.insert(running);

        GenerationTaskVO second = taskService.createPlanTask(chapterId, "重复提交");

        assertEquals(running.getId(), second.getId(), "已有活动任务时应返回该任务而非新建");
    }

    @Test
    void staleRunningTaskRecoveredOnStartup() {
        Long chapterId = newChapterId();
        GenerationTask zombie = new GenerationTask();
        zombie.setChapterId(chapterId);
        zombie.setTaskType("CONTENT");
        zombie.setStatus("RUNNING");
        taskMapper.insert(zombie);

        int recovered = taskService.recoverStaleTasks();

        assertTrue(recovered >= 1);
        assertEquals("FAILED", taskMapper.selectById(zombie.getId()).getStatus());
    }

    @Test
    void rewriteReplacesSelectionInContent() {
        Long chapterId = newChapterId();
        ChapterSaveRequest save = new ChapterSaveRequest();
        save.setTitle("第一章");
        save.setContent("开头段落。中间要改写的一句话。结尾段落。");
        chapterService.update(chapterId, save);

        GenerationTaskRequest req = new GenerationTaskRequest();
        req.setTaskType("REWRITE");
        req.setSelection("中间要改写的一句话。");
        GenerationTaskVO task = taskService.createTask(chapterId, req);

        assertEquals("SUCCEEDED", task.getStatus());
        String content = chapterService.requireById(chapterId).getContent();
        assertTrue(content.startsWith("开头段落。"), "选中文本前的内容保持不变");
        assertTrue(content.contains("【改写结果】"), "选中文本被替换为改写结果");
        assertTrue(content.endsWith("结尾段落。"), "选中文本后的内容保持不变");
    }
}
