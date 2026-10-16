package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mojian.agent.entity.AgentConversation;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {

    default List<AgentConversation> selectByProject(Long projectId, String agentRole) {
        return selectList(Wrappers.<AgentConversation>lambdaQuery()
                .eq(AgentConversation::getProjectId, projectId)
                .eq(agentRole != null, AgentConversation::getAgentRole, agentRole)
                .orderByDesc(AgentConversation::getUpdatedAt));
    }
}
