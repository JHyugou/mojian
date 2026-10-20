package com.mojian.agent;

import com.mojian.agent.entity.ChapterSupplement;
import com.mojian.agent.service.ChapterSupplementService;
import com.mojian.agent.tool.impl.VerifyChapterSupplementTool;
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

/** 策划确认补充要求：受作者设置控制，只允许确认待核验条目。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VerifyChapterSupplementToolTest {

    @Autowired
    private VerifyChapterSupplementTool tool;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;
    @Autowired
    private ChapterSupplementService supplementService;

    private Chapter chapter() {
        ProjectSaveRequest project = new ProjectSaveRequest();
        project.setName("补充核验测试");
        Long projectId = projectService.create(project).getId();
        ChapterSaveRequest request = new ChapterSaveRequest();
        request.setTitle("第一章");
        request.setContent("正文内容");
        return chapterService.create(projectId, request);
    }

    @Test
    @DisplayName("允许时策划可确认待核验条目；已核验不能重复确认")
    void 确认待核验条目() {
        Chapter chapter = chapter();
        ChapterSupplement supplement = supplementService.create(chapter.getProjectId(), chapter.getId(), "改结尾", "测试");
        supplementService.markSubmitted(List.of(supplement));
        AgentContext context = AgentContext.ofConversation(chapter.getProjectId(), AgentRole.PLANNER,
                null, true);

        String result = tool.execute(context, Map.of("supplementId", supplement.getId(), "reason", "已看到改动"));

        assertThat(result).contains("已确认补充要求").contains("已落实");
        assertThat(supplementService.require(supplement.getId()).getStatus()).isEqualTo("APPLIED");
        assertThat(tool.execute(context, Map.of("supplementId", supplement.getId())))
                .contains("只有待核验");
    }

    @Test
    @DisplayName("作者关闭权限后策划不能确认")
    void 关闭权限后拒绝() {
        Chapter chapter = chapter();
        ChapterSupplement supplement = supplementService.create(chapter.getProjectId(), chapter.getId(), "改结尾", "测试");
        supplementService.markSubmitted(List.of(supplement));
        AgentContext context = AgentContext.ofConversation(chapter.getProjectId(), AgentRole.PLANNER,
                null, false);

        String result = tool.execute(context, Map.of("supplementId", supplement.getId()));

        assertThat(result).contains("关闭").contains("不能确认");
        assertThat(supplementService.require(supplement.getId()).getStatus()).isEqualTo("SUBMITTED");
    }
}
