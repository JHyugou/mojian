package com.mojian.outline.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.outline.entity.PlotOutline;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface PlotOutlineMapper extends BaseMapper<PlotOutline> {

    default List<PlotOutline> selectByProjectId(Long projectId) {
        return selectList(new LambdaQueryWrapper<PlotOutline>()
                .eq(PlotOutline::getProjectId, projectId));
    }

    default int deleteByProjectId(Long projectId) {
        return delete(new LambdaQueryWrapper<PlotOutline>()
                .eq(PlotOutline::getProjectId, projectId));
    }
}
