package com.mojian.chapter;

import com.mojian.chapter.dto.ChapterMemorySaveRequest;
import com.mojian.chapter.vo.BookVO;
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
        assertNull(memory.getMemory());
    }

    @Test
    void saveMemoryUpsertsSingleRow() {
        Long chapterId = newChapterId("第一章", null);

        ChapterMemorySaveRequest first = new ChapterMemorySaveRequest();
        first.setMemory("第一次摘要");
        chapterService.saveMemory(chapterId, first);

        ChapterMemorySaveRequest second = new ChapterMemorySaveRequest();
        second.setMemory("第二次摘要；身世之谜未解开");
        chapterService.saveMemory(chapterId, second);

        ChapterMemory memory = chapterService.getMemory(chapterId);
        assertEquals("第二次摘要；身世之谜未解开", memory.getMemory());
        assertNotNull(memory.getId());
    }

    @Test
    void deleteChapterRemovesMemory() {
        Long chapterId = newChapterId("第一章", "正文");
        ChapterMemorySaveRequest memoryReq = new ChapterMemorySaveRequest();
        memoryReq.setMemory("摘要");
        chapterService.saveMemory(chapterId, memoryReq);

        chapterService.delete(chapterId);

        assertNull(chapterMemoryMapper.selectByChapterId(chapterId));
        assertThrows(Exception.class, () -> chapterService.requireById(chapterId));
    }

    @Test
    void bookAssemblesChaptersInCreationOrder() {
        Long projectId = newProjectId();
        ChapterSaveRequest first = new ChapterSaveRequest();
        first.setTitle("第一章");
        first.setContent("第一章正文。");
        first.setStatus("WRITING");
        Long c1 = chapterService.create(projectId, first).getId();

        ChapterSaveRequest draft = new ChapterSaveRequest();
        draft.setTitle("第二章（草稿）");
        draft.setContent("还没写完的草稿。");
        chapterService.create(projectId, draft);

        ChapterSaveRequest third = new ChapterSaveRequest();
        third.setTitle("第三章");
        third.setContent("第三章正文，篇幅更长一些。");
        third.setStatus("COMPLETED");
        chapterService.create(projectId, third);

        BookVO book = chapterService.book(projectId);

        assertEquals(2, book.getChapterCount(), "草稿章节不收录成书");
        assertEquals(2, book.getChapters().size());
        assertEquals("第一章", book.getChapters().get(0).getTitle(), "按创建顺序装配");
        assertEquals("第一章正文。", book.getChapters().get(0).getContent());
        assertEquals("第三章", book.getChapters().get(1).getTitle());
        assertEquals("第一章正文。".length() + "第三章正文，篇幅更长一些。".length(),
                book.getTotalChars());
        assertNotNull(book.getProjectName());
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
