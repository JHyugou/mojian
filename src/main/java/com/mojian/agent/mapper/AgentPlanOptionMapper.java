package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mojian.agent.entity.AgentPlanOption;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentPlanOptionMapper extends BaseMapper<AgentPlanOption> {

    default List<AgentPlanOption> selectByConversation(Long conversationId) {
        return selectList(Wrappers.<AgentPlanOption>lambdaQuery()
                .eq(AgentPlanOption::getConversationId, conversationId)
                .orderByAsc(AgentPlanOption::getOptionIndex));
    }

    /** 同一条消息产出的方案（重新生成时用来清理旧方案） */
    default List<AgentPlanOption> selectByMessage(Long messageId) {
        return selectList(Wrappers.<AgentPlanOption>lambdaQuery()
                .eq(AgentPlanOption::getMessageId, messageId)
                .orderByAsc(AgentPlanOption::getOptionIndex));
    }
}
