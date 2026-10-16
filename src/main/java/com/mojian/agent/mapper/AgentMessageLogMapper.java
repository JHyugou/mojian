package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mojian.agent.entity.AgentMessageLog;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentMessageLogMapper extends BaseMapper<AgentMessageLog> {

    /** 按时间顺序取整个会话的消息 —— 顺序就是"包裹"的组装顺序，不能乱 */
    default List<AgentMessageLog> selectByConversation(Long conversationId) {
        return selectList(Wrappers.<AgentMessageLog>lambdaQuery()
                .eq(AgentMessageLog::getConversationId, conversationId)
                .orderByAsc(AgentMessageLog::getId));
    }

    default long countByConversation(Long conversationId) {
        return selectCount(Wrappers.<AgentMessageLog>lambdaQuery()
                .eq(AgentMessageLog::getConversationId, conversationId));
    }
}
