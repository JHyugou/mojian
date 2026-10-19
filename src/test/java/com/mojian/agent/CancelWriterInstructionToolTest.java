package com.mojian.agent;

import com.mojian.agent.entity.WriterInstruction;
import com.mojian.agent.service.WriterInstructionService;
import com.mojian.agent.tool.impl.CancelWriterInstructionTool;
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

/** 策划撤回写作指令：只能撤回待执行，已执行/跨项目不能撤回。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CancelWriterInstructionToolTest {

    @Autowired
    private CancelWriterInstructionTool tool;
    @Autowired
    private WriterInstructionService instructionService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;

    private Long project(String name) {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName(name);
        return projectService.create(request).getId();
    }

    private Chapter chapter(Long projectId, String title) {
        ChapterSaveRequest request = new ChapterSaveRequest();
        request.setTitle(title);
        return chapterService.create(projectId, request);
    }

    @Test
    @DisplayName("待执行指令可撤回；撤回后不再进入待执行队列")
    void 撤回待执行指令() {
        Long projectId = project("撤回指令测试");
        Chapter chapter = chapter(projectId, "第一章");
        WriterInstruction instruction = instructionService.create(projectId, chapter.getId(),
                "这一章要写得更冷一点。", "测试");

        String result = tool.execute(AgentContext.of(projectId, AgentRole.PLANNER),
                Map.of("instructionId", instruction.getId(), "reason", "和上一条冲突"));

        assertThat(result).contains("已撤回").contains("#" + instruction.getId());
        assertThat(instructionService.require(instruction.getId()).getStatus()).isEqualTo("CANCELLED");
        assertThat(instructionService.pending(projectId, chapter.getId())).isEmpty();
    }

    @Test
    @DisplayName("已执行/已撤回的指令不能再次撤回；跨项目不能撤回")
    void 安全校验() {
        Long projectId = project("撤回安全测试");
        Long otherProject = project("另一个项目");
        Chapter chapter = chapter(projectId, "第一章");
        WriterInstruction instruction = instructionService.create(projectId, chapter.getId(), "内容", "理由");

        instructionService.cancel(instruction.getId());
        String second = tool.execute(AgentContext.of(projectId, AgentRole.PLANNER),
                Map.of("instructionId", instruction.getId()));
        assertThat(second).contains("只有待执行");

        WriterInstruction foreign = instructionService.create(otherProject, chapter.getId(), "别家指令", "理由");
        String cross = tool.execute(AgentContext.of(projectId, AgentRole.PLANNER),
                Map.of("instructionId", foreign.getId()));
        assertThat(cross).contains("不属于当前项目");
    }
}
