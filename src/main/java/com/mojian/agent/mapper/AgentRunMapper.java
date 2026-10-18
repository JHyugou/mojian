package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.agent.entity.AgentRun;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRun> {

    default List<AgentRun> selectRecent(int limit) {
        return selectList(new LambdaQueryWrapper<AgentRun>()
                .orderByDesc(AgentRun::getId)
                .last("limit " + Math.max(1, Math.min(limit, 200))));
    }

    default List<AgentRun> selectByProject(Long projectId, int limit) {
        return selectList(new LambdaQueryWrapper<AgentRun>()
                .eq(AgentRun::getProjectId, projectId)
                .orderByDesc(AgentRun::getId)
                .last("limit " + Math.max(1, Math.min(limit, 200))));
    }
}
