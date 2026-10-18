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

    default int markSubmitted(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return update(null, new LambdaUpdateWrapper<ChapterSupplement>()
                .in(ChapterSupplement::getId, ids)
                .in(ChapterSupplement::getStatus, "PENDING", "EXECUTING")
                .set(ChapterSupplement::getStatus, "SUBMITTED")
                .set(ChapterSupplement::getExecutingTaskId, null));
    }

    default int markVerified(Long supplementId) {
        return update(null, new LambdaUpdateWrapper<ChapterSupplement>()
                .eq(ChapterSupplement::getId, supplementId)
                .eq(ChapterSupplement::getStatus, "SUBMITTED")
                .set(ChapterSupplement::getStatus, "APPLIED")
                .set(ChapterSupplement::getExecutingTaskId, null));
    }

    default int returnToPending(Long supplementId) {
        return update(null, new LambdaUpdateWrapper<ChapterSupplement>()
                .eq(ChapterSupplement::getId, supplementId)
                .eq(ChapterSupplement::getStatus, "SUBMITTED")
                .set(ChapterSupplement::getStatus, "PENDING")
                .set(ChapterSupplement::getExecutingTaskId, null));
    }

    default int markApplied(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return update(null, new LambdaUpdateWrapper<ChapterSupplement>()
                .in(ChapterSupplement::getId, ids)
                .set(ChapterSupplement::getStatus, "APPLIED")
                .set(ChapterSupplement::getExecutingTaskId, null));
    }

    default int markExecuting(Long supplementId, Long taskId) {
        return update(null, new LambdaUpdateWrapper<ChapterSupplement>()
                .eq(ChapterSupplement::getId, supplementId)
                .eq(ChapterSupplement::getStatus, "PENDING")
                .set(ChapterSupplement::getStatus, "EXECUTING")
                .set(ChapterSupplement::getExecutingTaskId, taskId));
    }

    default int resetExecuting(Long supplementId) {
        return update(null, new LambdaUpdateWrapper<ChapterSupplement>()
                .eq(ChapterSupplement::getId, supplementId)
                .eq(ChapterSupplement::getStatus, "EXECUTING")
                .set(ChapterSupplement::getStatus, "PENDING")
                .set(ChapterSupplement::getExecutingTaskId, null));
    }

    default ChapterSupplement selectByExecutingTask(Long taskId) {
        return selectOne(new LambdaQueryWrapper<ChapterSupplement>()
                .eq(ChapterSupplement::getExecutingTaskId, taskId)
                .last("limit 1"));
    }
}
