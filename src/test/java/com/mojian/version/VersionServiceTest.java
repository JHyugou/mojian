package com.mojian.version;


import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.service.ProjectService;
import com.mojian.version.dto.RollbackRequest;
import com.mojian.version.entity.ChapterVersion;
import com.mojian.version.service.VersionService;
import com.mojian.version.vo.VersionVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;



import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VersionServiceTest {

    @Autowired
    private VersionService versionService;
    @Autowired
    private ChapterService chapterService;
    @Autowired
    private ProjectService projectService;

    private Long newChapterId() {
        ProjectSaveRequest req = new ProjectSaveRequest();
        req.setName("版本测试作品");
        Long projectId = projectService.create(req).getId();
        ChapterSaveRequest chapterReq = new ChapterSaveRequest();
        chapterReq.setTitle("第一章");
        return chapterService.create(projectId, chapterReq).getId();
    }

    private ChapterVersion insertVersion(Long chapterId, int no, String content) {
        ChapterVersion version = new ChapterVersion();
        version.setChapterId(chapterId);
        version.setVersionNo(no);
        version.setContent(content);
        version.setSource("MANUAL");
        versionMapperInsert(version);
        return version;
    }

    @Autowired
    private com.mojian.version.mapper.ChapterVersionMapper versionMapper;

    private void versionMapperInsert(ChapterVersion version) {
        versionMapper.insert(version);
    }

    @Test
    void rollbackCreatesNewVersionAndKeepsHistory() {
        Long chapterId = newChapterId();
        ChapterVersion v1 = insertVersion(chapterId, 1, "第一版正文");
        insertVersion(chapterId, 2, "第二版正文");

        RollbackRequest request = new RollbackRequest();
        request.setVersionId(v1.getId());

        VersionVO rolled = versionService.rollback(chapterId, request);

        assertEquals(3, rolled.getVersionNo(), "回滚应创建新版本 v3 而非删除历史");
        assertEquals("ROLLBACK", rolled.getSource());
        assertEquals("回滚至 v1", rolled.getRemark());

        Chapter chapter = chapterService.requireById(chapterId);
        assertEquals("第一版正文", chapter.getContent(), "工作区内容应回到 v1");
        assertEquals(3, chapter.getCurrentVersionNo());
        assertEquals(3, versionService.listVersions(chapterId).size(), "历史版本必须全部保留");
    }

    @Test
    void rollbackRejectsVersionFromOtherChapter() {
        Long chapterA = newChapterId();
        Long chapterB = newChapterId();
        ChapterVersion vInB = insertVersion(chapterB, 1, "B 的版本");

        RollbackRequest request = new RollbackRequest();
        request.setVersionId(vInB.getId());

        assertThrows(Exception.class, () -> versionService.rollback(chapterA, request));
    }

    @Test
    void manualSaveWithVersionFlagCreatesSnapshot() {
        Long chapterId = newChapterId();
        ChapterSaveRequest save = new ChapterSaveRequest();
        save.setTitle("第一章");
        save.setContent("值得留档的重要修改");
        save.setCreateVersion(true);
        save.setRemark("定稿一稿");

        chapterService.update(chapterId, save);

        List<VersionVO> versions = versionService.listVersions(chapterId);
        assertEquals(1, versions.size());
        assertEquals("MANUAL", versions.get(0).getSource());
        assertEquals("定稿一稿", versions.get(0).getRemark());
        assertEquals(1, chapterService.requireById(chapterId).getCurrentVersionNo());

        ChapterVersion detail = versionService.getVersion(versions.get(0).getId());
        assertEquals("值得留档的重要修改", detail.getContent());
    }

    @Test
    void manualSaveWithoutFlagCreatesNoVersion() {
        Long chapterId = newChapterId();
        ChapterSaveRequest save = new ChapterSaveRequest();
        save.setTitle("第一章");
        save.setContent("普通修改");

        chapterService.update(chapterId, save);

        assertEquals(0, versionService.listVersions(chapterId).size());
    }

    @Test
    void compareReturnsLineDiff() {
        Long chapterId = newChapterId();
        ChapterVersion v1 = insertVersion(chapterId, 1, "第一行\n第二行\n第三行");
        ChapterVersion v2 = insertVersion(chapterId, 2, "第一行\n第二行改\n第三行");

        var vo = versionService.compare(v1.getId(), v2.getId());

        assertEquals(1, vo.getBaseVersionNo());
        assertEquals(2, vo.getTargetVersionNo());
        assertTrue(vo.getLines().stream().anyMatch(l -> "del".equals(l.getType()) && "第二行".equals(l.getText())));
        assertTrue(vo.getLines().stream().anyMatch(l -> "add".equals(l.getType()) && "第二行改".equals(l.getText())));
    }
}
