package com.mojian.agent.dto;

/**
 * 触发一次写作任务。
 *
 * @param instruction       给写作 Agent 的额外指令（可空）。为空时按本章蓝图与写作要求自动生成，
 *                          并把"参考篇幅与已有章节相当"一并带上。
 * @param autoApproveDelete 作者是否在设置里开启了"自动批准删除章节"。
 *                          false（默认）时 Agent 只能提交删除申请，等作者确认；
 *                          true 时允许 Agent 当场删除（界面会有明确风险提示）。
 */
public record WriterRunRequest(String instruction, boolean autoApproveDelete) {
}
