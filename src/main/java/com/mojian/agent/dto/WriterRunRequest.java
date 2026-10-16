package com.mojian.agent.dto;

/**
 * 触发一次写作任务。
 *
 * @param instruction 给写作 Agent 的额外指令（可空）。为空时按本章蓝图与写作要求自动生成，
 *                    并把"参考篇幅与已有章节相当"一并带上
 */
public record WriterRunRequest(String instruction) {
}
