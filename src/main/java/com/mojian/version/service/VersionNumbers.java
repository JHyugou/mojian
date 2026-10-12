package com.mojian.version.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mojian.version.entity.ChapterVersion;
import com.mojian.version.mapper.ChapterVersionMapper;

/**
 * 版本号分配的统一口径（FR-016）：
 *
 *   新版本号 = max(章节指针, 现存最大版本号) + 1
 *
 * 以现存数据为准做防御——正常流程下指针等于最大版本号，但指针一旦异常落后，
 * 直接"指针 + 1"会撞 (chapter_id, version_no) 唯一键；取两者最大值可自愈。
 *
 * 独立成工具类而非放在 VersionService：章节手动保存（ChapterService）、
 * AI 正文落库（GenerationTaskService）与回滚（VersionService）共用同一规则，
 * 且服务之间直接互引会造成循环依赖。
 */
public final class VersionNumbers {

    private VersionNumbers() {
    }

    public static int next(ChapterVersionMapper mapper, Long chapterId, Integer pointerNo) {
        ChapterVersion latest = mapper.selectOne(new LambdaQueryWrapper<ChapterVersion>()
                .eq(ChapterVersion::getChapterId, chapterId)
                .orderByDesc(ChapterVersion::getVersionNo)
                .last("LIMIT 1"));
        int maxExisting = latest == null ? 0 : latest.getVersionNo();
        int pointer = pointerNo == null ? 0 : pointerNo;
        return Math.max(maxExisting, pointer) + 1;
    }
}
