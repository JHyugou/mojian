package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.agent.entity.AgentDeleteRequest;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentDeleteRequestMapper extends BaseMapper<AgentDeleteRequest> {

    default List<AgentDeleteRequest> selectPending(Long projectId) {
        return selectList(new LambdaQueryWrapper<AgentDeleteRequest>()
                .eq(AgentDeleteRequest::getProjectId, projectId)
                .eq(AgentDeleteRequest::getStatus, "PENDING")
                .orderByAsc(AgentDeleteRequest::getCreatedAt));
    }

    default AgentDeleteRequest selectPendingByEntity(Long projectId, String entityType, Long entityId) {
        return selectOne(new LambdaQueryWrapper<AgentDeleteRequest>()
                .eq(AgentDeleteRequest::getProjectId, projectId)
                .eq(AgentDeleteRequest::getEntityType, entityType)
                .eq(AgentDeleteRequest::getEntityId, entityId)
                .eq(AgentDeleteRequest::getStatus, "PENDING")
                .last("limit 1"));
    }
}
