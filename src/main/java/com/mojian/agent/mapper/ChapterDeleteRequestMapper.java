package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.agent.entity.ChapterDeleteRequest;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ChapterDeleteRequestMapper extends BaseMapper<ChapterDeleteRequest> {

    default List<ChapterDeleteRequest> selectRecent(Long projectId, int limit) {
        return selectList(new LambdaQueryWrapper<ChapterDeleteRequest>()
                .eq(ChapterDeleteRequest::getProjectId, projectId)
                .orderByDesc(ChapterDeleteRequest::getCreatedAt)
                .last("limit " + Math.max(1, Math.min(limit, 100))));
    }

    default List<ChapterDeleteRequest> selectPending(Long projectId) {
        return selectList(new LambdaQueryWrapper<ChapterDeleteRequest>()
                .eq(ChapterDeleteRequest::getProjectId, projectId)
                .eq(ChapterDeleteRequest::getStatus, "PENDING")
                .orderByAsc(ChapterDeleteRequest::getCreatedAt));
    }
}
