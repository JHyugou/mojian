package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mojian.agent.entity.AgentUndoLog;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentUndoLogMapper extends BaseMapper<AgentUndoLog> {

    default List<AgentUndoLog> selectAfterTurn(Long conversationId, int turnIndex) {
        return selectList(new LambdaQueryWrapper<AgentUndoLog>()
                .eq(AgentUndoLog::getConversationId, conversationId)
                .gt(AgentUndoLog::getTurnIndex, turnIndex)
                .orderByDesc(AgentUndoLog::getId));
    }
}
