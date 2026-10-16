package com.mojian.agent;

/**
 * 一步工具调用的记录。
 *
 * <p>这份记录有两个用途，都很重要：
 * <ol>
 *   <li>给用户看"它到底干了什么"（不透明的 Agent 没人敢用）</li>
 *   <li>出问题时唯一的排查线索 —— Agent 的错误通常不报异常，只是结果不对，
 *       只能靠回溯这份 trace 才能知道它哪一步想歪了</li>
 * </ol>
 */
public record AgentStep(int step, String toolName, String arguments, String result) {
}
