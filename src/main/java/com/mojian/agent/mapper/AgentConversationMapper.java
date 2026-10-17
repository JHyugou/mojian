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

    /**
     * 全部项目下的会话（可按角色过滤），最近用过的在前。
     *
     * <p>左侧的工作区树要一次拿到"所有项目 + 各自的会话"，
     * 按项目逐个请求既慢又要等 N 次网络往返。
     */
    default List<AgentConversation> selectAll(String agentRole) {
        return selectList(Wrappers.<AgentConversation>lambdaQuery()
                .eq(agentRole != null, AgentConversation::getAgentRole, agentRole)
                .orderByDesc(AgentConversation::getUpdatedAt));
    }
}
