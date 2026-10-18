package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.agent.entity.ChapterSupplement;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface ChapterSupplementMapper extends BaseMapper<ChapterSupplement> {

    default List<ChapterSupplement> selectByChapter(Long chapterId) {
        return selectList(new LambdaQueryWrapper<ChapterSupplement>()
                .eq(ChapterSupplement::getChapterId, chapterId)
                .orderByAsc(ChapterSupplement::getCreatedAt));
    }

    default List<ChapterSupplement> selectPending(Long chapterId) {
        return selectList(new LambdaQueryWrapper<ChapterSupplement>()
                .eq(ChapterSupplement::getChapterId, chapterId)
                .eq(ChapterSupplement::getStatus, "PENDING")
                .orderByAsc(ChapterSupplement::getCreatedAt));
    }

    default int markApplied(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return update(null, new LambdaUpdateWrapper<ChapterSupplement>()
                .in(ChapterSupplement::getId, ids)
                .set(ChapterSupplement::getStatus, "APPLIED"));
    }
}
