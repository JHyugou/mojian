package com.mojian.version.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.mapper.ChapterMapper;
import com.mojian.chapter.service.ChapterService;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.version.dto.RollbackRequest;
import com.mojian.version.entity.ChapterVersion;
import com.mojian.version.mapper.ChapterVersionMapper;
import com.mojian.version.vo.VersionCompareVO;
import com.mojian.version.vo.VersionDiffLine;
import com.mojian.version.vo.VersionVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 章节版本服务（FR-016 / FR-017）：
 * 版本不可变（只插入不更新）；回滚 = 以目标版本内容新建版本，历史全部保留。
 */
@Service
@RequiredArgsConstructor
public class VersionService extends ServiceImpl<ChapterVersionMapper, ChapterVersion> {

    private final ChapterService chapterService;
    private final ChapterMapper chapterMapper;

    public List<VersionVO> listVersions(Long chapterId) {
        chapterService.requireById(chapterId);
        return lambdaQuery()
                .eq(ChapterVersion::getChapterId, chapterId)
                .orderByDesc(ChapterVersion::getVersionNo)
                .list()
                .stream()
                .map(VersionVO::from)
                .toList();
    }

    public ChapterVersion getVersion(Long versionId) {
        return requireById(versionId);
    }

    /**
     * 版本对比：按行 diff 两个不可变版本，返回新增/删除/相同行。
     */
    public VersionCompareVO compare(Long baseVersionId, Long targetVersionId) {
        ChapterVersion base = requireById(baseVersionId);
        ChapterVersion target = requireById(targetVersionId);
        VersionCompareVO vo = new VersionCompareVO();
        vo.setBaseVersionId(base.getId());
        vo.setBaseVersionNo(base.getVersionNo());
        vo.setTargetVersionId(target.getId());
        vo.setTargetVersionNo(target.getVersionNo());
        vo.setLines(computeDiff(base.getContent(), target.getContent()));
        return vo;
    }

    private List<VersionDiffLine> computeDiff(String oldText, String newText) {
        String[] a = splitLines(oldText);
        String[] b = splitLines(newText);
        int start = 0;
        while (start < a.length && start < b.length && a[start].equals(b[start])) {
            start++;
        }
        int endA = a.length;
        int endB = b.length;
        while (endA > start && endB > start && a[endA - 1].equals(b[endB - 1])) {
            endA--;
            endB--;
        }
        List<VersionDiffLine> lines = new ArrayList<>();
        for (int i = 0; i < start; i++) {
            lines.add(VersionDiffLine.of("same", a[i]));
        }
        for (int i = start; i < endA; i++) {
            lines.add(VersionDiffLine.of("del", a[i]));
        }
        for (int i = start; i < endB; i++) {
            lines.add(VersionDiffLine.of("add", b[i]));
        }
        for (int i = endA; i < a.length; i++) {
            lines.add(VersionDiffLine.of("same", a[i]));
        }
        return lines;
    }

    private String[] splitLines(String content) {
        if (content == null) {
            return new String[0];
        }
        return content.split("\\r?\\n", -1);
    }

    /**
     * 回滚：创建一个内容等于目标版本的新版本，并把章节工作区指向它。
     * 不删除、不修改任何历史版本（FR-017）。
     */
    @Transactional(rollbackFor = Exception.class)
    public VersionVO rollback(Long chapterId, RollbackRequest request) {
        Chapter chapter = chapterService.requireById(chapterId);
        ChapterVersion target = requireById(request.getVersionId());
        if (!target.getChapterId().equals(chapterId)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "目标版本不属于该章节");
        }

        int newVersionNo = nextVersionNo(chapterId, chapter.getCurrentVersionNo());
        ChapterVersion rollbackVersion = new ChapterVersion();
        rollbackVersion.setChapterId(chapterId);
        rollbackVersion.setVersionNo(newVersionNo);
        rollbackVersion.setContent(target.getContent());
        rollbackVersion.setSource("ROLLBACK");
        rollbackVersion.setRemark("回滚至 v" + target.getVersionNo());
        save(rollbackVersion);

        chapter.setContent(target.getContent());
        chapter.setCurrentVersionNo(newVersionNo);
        chapterMapper.updateById(chapter);

        return VersionVO.from(rollbackVersion);
    }

    public ChapterVersion requireById(Long versionId) {
        ChapterVersion version = getById(versionId);
        if (version == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "章节版本不存在");
        }
        return version;
    }

    /**
     * 新版本号 = max(章节指针, 现存最大版本号) + 1。
     * 以现存数据为准做防御：即使指针落后也不会撞唯一键。
     */
    private int nextVersionNo(Long chapterId, Integer pointerNo) {
        ChapterVersion latest = lambdaQuery()
                .eq(ChapterVersion::getChapterId, chapterId)
                .orderByDesc(ChapterVersion::getVersionNo)
                .last("LIMIT 1")
                .one();
        int maxExisting = latest == null ? 0 : latest.getVersionNo();
        int pointer = pointerNo == null ? 0 : pointerNo;
        return Math.max(maxExisting, pointer) + 1;
    }
}
