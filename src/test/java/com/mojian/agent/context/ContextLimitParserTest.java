package com.mojian.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文上限解析测试。
 *
 * <p>用例里的错误文本是 2026-10-16 从 OpenCode Go 真实抓到的响应原文，
 * 不是编的 —— 解析规则必须扛得住真实报文。
 */
class ContextLimitParserTest {

    @Test
    @DisplayName("从真实超限错误里解析出上限（单行原文）")
    void 解析真实错误信息() {
        String real = "{\"error\":{\"param\":null,\"type\":\"invalid_request_error\",\"code\":\"invalid_request_error\","
                + "\"message\":\"Upstream request failed: [invalid_request_error] This model's maximum context length "
                + "is 1048576 tokens. However, you requested 2000046 tokens (2000030 in the messages, 16 in the "
                + "completion). Please reduce the length of the messages or completion.\"}}";

        assertThat(ContextLimitParser.parse(real)).hasValue(1_048_576);
    }

    @Test
    @DisplayName("报文被格式化成多行时也能解析（换行夹在关键词中间）")
    void 多行报文也能解析() {
        String prettyPrinted = """
                {"error":{"type":"invalid_request_error",
                "message":"Upstream request failed: This model's maximum context
                length is 1048576 tokens. However, you requested 2000046 tokens."}}
                """;

        assertThat(ContextLimitParser.parse(prettyPrinted)).hasValue(1_048_576);
    }

    @Test
    @DisplayName("硅基流动的措辞也能解析（换供应商不必改代码）")
    void 硅基流动格式() {
        String real = "{\"code\":20015,\"message\":\"number of input tokens (1500030) has exceeded "
                + "max_prompt_tokens (1048576) limit.\",\"data\":null}";

        assertThat(ContextLimitParser.parse(real)).hasValue(1_048_576);
    }

    @Test
    @DisplayName("大小写与空格变化也能解析")
    void 大小写不敏感() {
        assertThat(ContextLimitParser.parse("Maximum Context Length Is 32768 tokens.")).hasValue(32_768);
        assertThat(ContextLimitParser.parse("maximum context length is   131072   tokens"))
                .hasValue(131_072);
    }

    @Test
    @DisplayName("信息里没有上限时返回空：调用方保持原配置不变")
    void 无相关信息时返回空() {
        assertThat(ContextLimitParser.parse("{\"error\":{\"message\":\"rate limit exceeded\"}}")).isEmpty();
        assertThat(ContextLimitParser.parse("模型服务返回异常状态码 402：insufficient balance")).isEmpty();
    }

    @Test
    @DisplayName("空输入不抛异常")
    void 空输入安全() {
        assertThat(ContextLimitParser.parse(null)).isEmpty();
        assertThat(ContextLimitParser.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("数字大到溢出时返回空，而不是抛异常")
    void 溢出安全() {
        assertThat(ContextLimitParser.parse("maximum context length is 99999999999999 tokens")).isEmpty();
    }
}
