package com.mojian.agent;

import com.mojian.agent.entity.WriterInstruction;
import com.mojian.agent.service.WriterInstructionService;
import com.mojian.agent.tool.impl.InstructWriterTool;
import com.mojian.chapter.dto.ChapterSaveRequest;
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
 * 策划 → 写作 指令通道的工具测试：工具落库，写作侧能读到。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InstructWriterToolTest {

    @Autowired
    private InstructWriterTool tool;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;
    @Autowired
    private WriterInstructionService instructionService;

    private Long createProject() {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName("指令工具测试");
        return projectService.create(request).getId();
    }

    @Test
    @DisplayName("策划工具把提示词写进写作指令表，写作侧能按章读到")
    void 下发写作指令() {
        Long projectId = createProject();
        ChapterSaveRequest chapter = new ChapterSaveRequest();
        chapter.setTitle("第一章 测试章");
        chapterService.create(projectId, chapter);

        String result = tool.execute(AgentContext.of(projectId, AgentRole.PLANNER),
                Map.of("chapterNo", 1, "instruction", "开篇三句内出现沙暴，结尾留一个脚印。",
                        "reason", "立住危险感"));

        assertThat(result).contains("已把写作指令交给写作 Agent").contains("第 1 章");
        List<WriterInstruction> pending = instructionService.pending(projectId,
                chapterService.list(projectId).get(0).getId());
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getContent()).contains("沙暴");
        assertThat(pending.get(0).getSource()).isEqualTo("PLANNER");
        assertThat(pending.get(0).getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("章节不存在时给出可执行的错误，而不是写一条没有目标的指令")
    void 章节不存在时报错() {
        Long projectId = createProject();
        String result = tool.execute(AgentContext.of(projectId, AgentRole.PLANNER),
                Map.of("chapterNo", 3, "instruction", "随便写"));
        assertThat(result).contains("没有第 3 章");
        assertThat(instructionService.recent(projectId, 10)).isEmpty();
    }
}
