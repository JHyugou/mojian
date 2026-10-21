package com.mojian.agent;

import com.mojian.agent.tool.impl.InstructWriterTool;
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

/** 策划 → 写作要求：工具直接写章节的 writingRequirement，状态待执行。 */
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

    private Long createProject() {
        ProjectSaveRequest request = new ProjectSaveRequest();
        request.setName("指令工具测试");
        return projectService.create(request).getId();
    }

    @Test
    @DisplayName("策划工具把要求写进章节写作要求，并标记待执行")
    void 下发写作要求() {
        Long projectId = createProject();
        ChapterSaveRequest chapter = new ChapterSaveRequest();
        chapter.setTitle("第一章 测试章");
        chapterService.create(projectId, chapter);

        String result = tool.execute(AgentContext.of(projectId, AgentRole.PLANNER),
                Map.of("chapterNo", 1, "instruction", "开篇三句内出现沙暴，结尾留一个脚印。",
                        "reason", "立住危险感"));

        assertThat(result).contains("已把写作要求写入第 1 章").contains("执行写作");
        Chapter saved = chapterService.list(projectId).get(0);
        Chapter detail = chapterService.requireById(saved.getId());
        assertThat(detail.getWritingRequirement()).contains("沙暴");
        assertThat(detail.getWritingRequirementStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("写作成功后要求标记已执行；修改要求后回到待执行")
    void 执行状态流转() {
        Long projectId = createProject();
        ChapterSaveRequest chapter = new ChapterSaveRequest();
        chapter.setTitle("第一章 状态流转");
        Chapter created = chapterService.create(projectId, chapter);
        chapterService.updateWritingRequirement(created.getId(), "第三人称，短句。");

        Chapter executed = chapterService.markWritingRequirementExecuted(created.getId());
        assertThat(executed.getWritingRequirementStatus()).isEqualTo("EXECUTED");
        assertThat(executed.getWritingRequirementExecutedAt()).isNotNull();

        Chapter changed = chapterService.updateWritingRequirement(created.getId(), "换一种写法。");
        assertThat(changed.getWritingRequirementStatus()).isEqualTo("PENDING");
        assertThat(changed.getWritingRequirementExecutedAt()).isNull();
    }

    @Test
    @DisplayName("章节不存在时给出可执行的错误")
    void 章节不存在时报错() {
        Long projectId = createProject();
        String result = tool.execute(AgentContext.of(projectId, AgentRole.PLANNER),
                Map.of("chapterNo", 3, "instruction", "随便写"));
        assertThat(result).contains("没有第 3 章");
    }
}
