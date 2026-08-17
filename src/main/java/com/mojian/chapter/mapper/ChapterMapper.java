package com.mojian.chapter.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.chapter.entity.Chapter;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ChapterMapper extends BaseMapper<Chapter> {

    default List<Chapter> selectByProjectId(Long projectId) {
        return selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, projectId));
    }

    default int deleteByProjectId(Long projectId) {
        return delete(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, projectId));
    }

    /** 蓝图节点被删除时，解除章节与节点的关联（不删除章节） */
    default int clearOutlineNode(List<Long> outlineNodeIds) {
        return update(null, new LambdaUpdateWrapper<Chapter>()
                .in(Chapter::getOutlineNodeId, outlineNodeIds)
                .set(Chapter::getOutlineNodeId, null));
    }
}
