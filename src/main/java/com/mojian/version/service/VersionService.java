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
        if (!base.getChapterId().equals(target.getChapterId())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "两个版本不属于同一章节，无法对比");
        }
        VersionCompareVO vo = new VersionCompareVO();
        vo.setBaseVersionId(base.getId());
        vo.setBaseVersionNo(base.getVersionNo());
        vo.setTargetVersionId(target.getId());
        vo.setTargetVersionNo(target.getVersionNo());
        vo.setLines(computeDiff(base.getContent(), target.getContent()));
        return vo;
    }

    /** 中段差异超过该规模（行数乘积）时退化为整块标注，避免 O(n·m) 矩阵内存失控 */
    private static final long MAX_LCS_CELLS = 4_000_000L;

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
        appendMiddleDiff(lines,
                java.util.Arrays.copyOfRange(a, start, endA),
                java.util.Arrays.copyOfRange(b, start, endB));
        for (int i = endA; i < a.length; i++) {
            lines.add(VersionDiffLine.of("same", a[i]));
        }
        return lines;
    }

    /**
     * 中段差异按最长公共子序列（LCS）拆分：一章内多处分散修改也能精确到行，
     * 而不是把中间未变的文字整块标成"删+增"。规模超限时退化为先删后增的整块标注。
     */
    private void appendMiddleDiff(List<VersionDiffLine> out, String[] a, String[] b) {
        if (a.length == 0) {
            for (String line : b) {
                out.add(VersionDiffLine.of("add", line));
            }
            return;
        }
        if (b.length == 0) {
            for (String line : a) {
                out.add(VersionDiffLine.of("del", line));
            }
            return;
        }
        if ((long) a.length * b.length > MAX_LCS_CELLS) {
            for (String line : a) {
                out.add(VersionDiffLine.of("del", line));
            }
            for (String line : b) {
                out.add(VersionDiffLine.of("add", line));
            }
            return;
        }

        // dp[i][j] = a[i..] 与 b[j..] 的公共子序列长度，从后往前填表
        int[][] dp = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) {
            for (int j = b.length - 1; j >= 0; j--) {
                dp[i][j] = a[i].equals(b[j])
                        ? dp[i + 1][j + 1] + 1
                        : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        int ia = 0;
        int jb = 0;
        while (ia < a.length && jb < b.length) {
            if (a[ia].equals(b[jb])) {
                out.add(VersionDiffLine.of("same", a[ia]));
                ia++;
                jb++;
            } else if (dp[ia + 1][jb] >= dp[ia][jb + 1]) {
                out.add(VersionDiffLine.of("del", a[ia]));
                ia++;
            } else {
                out.add(VersionDiffLine.of("add", b[jb]));
                jb++;
            }
        }
        while (ia < a.length) {
            out.add(VersionDiffLine.of("del", a[ia++]));
        }
        while (jb < b.length) {
            out.add(VersionDiffLine.of("add", b[jb++]));
        }
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

        int newVersionNo = VersionNumbers.next(baseMapper, chapterId, chapter.getCurrentVersionNo());
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
}
