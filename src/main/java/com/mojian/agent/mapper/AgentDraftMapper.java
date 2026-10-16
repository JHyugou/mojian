package com.mojian.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mojian.agent.draft.DraftStatus;
import com.mojian.agent.entity.AgentDraft;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AgentDraftMapper extends BaseMapper<AgentDraft> {

    default List<AgentDraft> selectByConversation(Long conversationId) {
        return selectList(Wrappers.<AgentDraft>lambdaQuery()
                .eq(AgentDraft::getConversationId, conversationId)
                .orderByAsc(AgentDraft::getId));
    }

    /** 某个会话里还等着确认的草稿（界面上的"待确认"列表） */
    default List<AgentDraft> selectPendingByConversation(Long conversationId) {
        return selectList(Wrappers.<AgentDraft>lambdaQuery()
                .eq(AgentDraft::getConversationId, conversationId)
                .eq(AgentDraft::getStatus, DraftStatus.PENDING)
                .orderByAsc(AgentDraft::getId));
    }

    default List<AgentDraft> selectByProject(Long projectId) {
        return selectList(Wrappers.<AgentDraft>lambdaQuery()
                .eq(AgentDraft::getProjectId, projectId)
                .orderByDesc(AgentDraft::getId));
    }
}
