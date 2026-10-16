package com.mojian.agent.context;

/**
 * 组装结果：拼好的材料文本 + 报表。
 *
 * @param text   要注入提示词的材料正文
 * @param report 装填情况（装了什么、裁了什么、用了多少）
 */
public record AgentContextAssembly(String text, AgentContextReport report) {

    public boolean empty() {
        return text == null || text.isBlank();
    }
}
