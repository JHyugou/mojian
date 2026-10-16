package com.mojian.agent.dto;

/**
 * 设置本章写作要求（风格、人称、篇幅、禁忌等）。
 *
 * @param requirement 要求全文；空字符串表示清除
 */
public record WritingRequirementRequest(String requirement) {
}
