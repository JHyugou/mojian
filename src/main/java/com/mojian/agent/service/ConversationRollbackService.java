package com.mojian.agent.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 对话回滚（可选连带数据库撤销）。
 *
 * <p>两件事必须在同一个事务里：撤销业务数据、裁掉对话消息。
 * 只成功一半会留下"消息回去了但数据还在"或相反的分裂状态。
 */
@Service
@RequiredArgsConstructor
public class ConversationRollbackService {

    private final ConversationService conversationService;
    private final AgentUndoLogService undoLogService;

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> rollback(Long conversationId, int keepUserMessages, boolean rollbackDatabase) {
        int undone = 0;
        if (rollbackDatabase) {
            undone = undoLogService.undoAfter(conversationId, keepUserMessages);
        }
        int deleted = conversationService.rollbackToUserMessage(conversationId, keepUserMessages);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("undone", undone);
        result.put("rollbackDatabase", rollbackDatabase);
        return result;
    }
}
