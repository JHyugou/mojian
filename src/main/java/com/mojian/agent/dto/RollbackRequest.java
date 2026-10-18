package com.mojian.agent.dto;

/**
 * 对话回滚请求。
 *
 * @param keepUserMessages 保留前几条用户消息
 * @param rollbackDatabase 是否同时撤销这之后的数据库改动（人物/设定/蓝图）
 */
public record RollbackRequest(Integer keepUserMessages, Boolean rollbackDatabase) {

    public int keep() {
        return keepUserMessages == null ? 0 : Math.max(0, keepUserMessages);
    }

    public boolean withDatabase() {
        return Boolean.TRUE.equals(rollbackDatabase);
    }
}
