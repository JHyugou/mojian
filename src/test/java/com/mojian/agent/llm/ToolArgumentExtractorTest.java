package com.mojian.agent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具参数的内容抽取。
 *
 * <p>它守的是"边写边看"：正文装在 {@code save_chapter_draft} 的 arguments 里，
 * 抽不出来，用户就要在模型写正文的一两分钟里盯着空白。
 */
class ToolArgumentExtractorTest {

    private String feedAll(String json, int chunkSize) {
        ToolArgumentExtractor extractor = new ToolArgumentExtractor("content");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < json.length(); i += chunkSize) {
            out.append(extractor.append(json.substring(i, Math.min(json.length(), i + chunkSize))));
        }
        return out.toString();
    }

    @Test
    @DisplayName("整包到达时能抽出内容")
    void 整包抽取() {
        String json = "{\"content\":\"午后日头把官道晒得发白。\",\"note\":\"改了开头\"}";
        assertThat(feedAll(json, json.length())).isEqualTo("午后日头把官道晒得发白。");
    }

    @Test
    @DisplayName("⭐ 分片到达时也能抽出（转义可能被切在两片之间）")
    void 分片抽取() {
        // \n 在 JSON 里是两个字符，分片很可能正好切在它们中间
        String json = "{\"content\":\"第一行\\n\\n第二行\\t带制表符\",\"note\":\"说明\"}";
        for (int size : new int[] {1, 2, 3, 5, 7, 13}) {
            assertThat(feedAll(json, size))
                    .as("分片大小 %d 时", size)
                    .isEqualTo("第一行\n\n第二行\t带制表符");
        }
    }

    @Test
    @DisplayName("content 不是第一个字段时也找得到")
    void 字段顺序无关() {
        String json = "{\"note\":\"先说了说明\",\"content\":\"后面的正文\"}";
        assertThat(feedAll(json, 4)).isEqualTo("后面的正文");
    }

    @Test
    @DisplayName("内容里含引号与反斜杠时能正确还原")
    void 转义处理() {
        String json = "{\"content\":\"他说：\\\"走。\\\" 然后笑了\\\\收剑\"}";
        assertThat(feedAll(json, 3)).isEqualTo("他说：\"走。\" 然后笑了\\收剑");
    }

    @Test
    @DisplayName("参数里没有这个字段时不产出任何东西（也不能崩）")
    void 没有该字段() {
        assertThat(feedAll("{\"note\":\"只有说明\"}", 5)).isEmpty();
    }

    @Test
    @DisplayName("有冒号后带空格的写法：容忍空白")
    void 容忍空白() {
        String json = "{\"content\" : \"带空格的写法\"}";
        assertThat(feedAll(json, 6)).isEqualTo("带空格的写法");
    }
}
