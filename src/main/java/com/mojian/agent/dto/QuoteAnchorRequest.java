package com.mojian.agent.dto;

/**
 * 把正文里选中的一段文字解析成可校验的编辑锚点。
 *
 * @param quotedText    用户选中的原文
 * @param contextBefore 选区前的一小段上下文，用于重复文字消歧
 * @param contextAfter  选区后的一小段上下文
 */
public record QuoteAnchorRequest(String quotedText, String contextBefore, String contextAfter) {
}
