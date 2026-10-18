package com.mojian.agent.dto;

/**
 * 触发一次写作任务。
 *
 * @param instruction       给写作 Agent 的额外指令（可空）。为空时按本章蓝图与写作要求自动生成。
 * @param autoApproveDelete 作者是否在设置里开启了"自动批准删除章节"。
 * @param supplementId      可选：只执行某一条审稿补充要求；为空表示普通写作（注入全部待执行补充要求）。
 */
public record WriterRunRequest(String instruction, boolean autoApproveDelete, Long supplementId) {
}
