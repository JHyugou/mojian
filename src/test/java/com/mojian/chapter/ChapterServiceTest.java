package com.mojian.chapter;

import com.mojian.chapter.dto.ChapterMemorySaveRequest;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.mapper.ChapterMemoryMapper;
import com.mojian.chapter.service.ChapterService;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChapterServiceTest {

    @Autowired
    private ChapterService chapterService;
    @Autowired
    private ChapterMemoryMapper chapterMemoryMapper;
    @Autowired
    private ProjectService projectService;

    private Long newProjectId() {
        ProjectSaveRequest req = new ProjectSaveRequest();
        req.setName("章节测试项目");
        return projectService.create(req).getId();
    }

    private Long newChapterId(String title, String content) {
        Long projectId = newProjectId();
        ChapterSaveRequest req = new ChapterSaveRequest();
        req.setTitle(title);
        req.setContent(content);
        return chapterService.create(projectId, req).getId();
    }

    @Test
    void listDoesNotLoadContent() {
        Long projectId = newProjectId();
        ChapterSaveRequest req = new ChapterSaveRequest();
        req.setTitle("第一章");
        req.setContent("很长的正文".repeat(100));
        chapterService.create(projectId, req);

        var list = chapterService.list(projectId);

        assertEquals(1, list.size());
        assertNull(list.get(0).getContent());
        assertEquals("第一章", list.get(0).getTitle());
    }

    @Test
    void getMemoryReturnsEmptyShellForNewChapter() {
        Long chapterId = newChapterId("第一章", null);

        ChapterMemory memory = chapterService.getMemory(chapterId);

        assertEquals(chapterId, memory.getChapterId());
        assertNull(memory.getSummary());
    }

    @Test
    void saveMemoryUpsertsSingleRow() {
        Long chapterId = newChapterId("第一章", null);

        ChapterMemorySaveRequest first = new ChapterMemorySaveRequest();
        first.setSummary("第一次摘要");
        chapterService.saveMemory(chapterId, first);

        ChapterMemorySaveRequest second = new ChapterMemorySaveRequest();
        second.setSummary("第二次摘要");
        second.setUnresolvedConflicts("身世之谜未解开");
        chapterService.saveMemory(chapterId, second);

        ChapterMemory memory = chapterService.getMemory(chapterId);
        assertEquals("第二次摘要", memory.getSummary());
        assertEquals("身世之谜未解开", memory.getUnresolvedConflicts());
        assertNotNull(memory.getId());
    }

    @Test
    void deleteChapterRemovesMemory() {
        Long chapterId = newChapterId("第一章", "正文");
        ChapterMemorySaveRequest memoryReq = new ChapterMemorySaveRequest();
        memoryReq.setSummary("摘要");
        chapterService.saveMemory(chapterId, memoryReq);

        chapterService.delete(chapterId);

        assertNull(chapterMemoryMapper.selectByChapterId(chapterId));
        assertThrows(Exception.class, () -> chapterService.requireById(chapterId));
    }

    @Test
    void createChapterWithMissingOutlineNodeFails() {
        Long projectId = newProjectId();
        ChapterSaveRequest req = new ChapterSaveRequest();
        req.setTitle("第一章");
        req.setOutlineNodeId(-1L);

        assertThrows(Exception.class, () -> chapterService.create(projectId, req));
    }
}
