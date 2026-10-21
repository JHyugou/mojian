package com.mojian.agent;

import com.mojian.agent.dto.QuoteAnchorRequest;
import com.mojian.agent.service.QuoteAnchorService;
import com.mojian.agent.vo.QuoteAnchorVO;
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

import static org.assertj.core.api.Assertions.assertThat;

/** 正文选区解析：章节/段落/字符/版本必须准确。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class QuoteAnchorServiceTest {

    @Autowired
    private QuoteAnchorService service;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ChapterService chapterService;

    private Chapter chapter(String content) {
        ProjectSaveRequest project = new ProjectSaveRequest();
        project.setName("引用锚点测试");
        Long projectId = projectService.create(project).getId();
        ChapterSaveRequest request = new ChapterSaveRequest();
        request.setTitle("第一章 锚点");
        request.setContent(content);
        return chapterService.create(projectId, request);
    }

    @Test
    @DisplayName("定位到章节、段落和字符区间")
    void 定位段落() {
        Chapter chapter = chapter("第一段内容。\n\n第二段里有一句话。\n\n第三段。");
        QuoteAnchorVO anchor = service.locate(chapter.getId(),
                new QuoteAnchorRequest("第二段里有一句话。", "第一段内容。\n\n", "\n\n第三段。"));

        assertThat(anchor.chapterNo()).isEqualTo(1);
        assertThat(anchor.paragraphStart()).isEqualTo(2);
        assertThat(anchor.paragraphEnd()).isEqualTo(2);
        assertThat(anchor.baseVersionNo()).isZero();
        assertThat(anchor.quotedText()).isEqualTo("第二段里有一句话。");
        assertThat(anchor.charStart()).isLessThan(anchor.charEnd());
    }

    @Test
    @DisplayName("重复句子用上下文选对位置")
    void 上下文消歧() {
        Chapter chapter = chapter("重复。\n\n中间段。\n\n重复。");
        QuoteAnchorVO anchor = service.locate(chapter.getId(),
                new QuoteAnchorRequest("重复。", "中间段。\n\n", ""));

        assertThat(anchor.paragraphStart()).isEqualTo(3);
    }
}
