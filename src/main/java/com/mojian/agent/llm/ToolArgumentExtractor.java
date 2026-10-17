package com.mojian.agent.llm;

/**
 * 从流式到达的工具参数里，实时抽出某个字符串字段的值。
 *
 * <h3>为什么需要它</h3>
 * 写作 Agent 的正文是通过 {@code save_chapter_draft(content="...")} 传的 ——
 * 五千字全在那串 JSON 参数里。而流式响应里，工具的 arguments 是<b>一片片到达的</b>：
 *
 * <pre>
 *   {"content":"午后日头把官道晒得发白。\n\n陌走在靠北的一侧。","note":"改了开头"}
 * </pre>
 *
 * 如果不在这里把 content 的值边收边取出来，用户就会在模型写的那两分钟里
 * 只看到一片空白，然后正文"啪"地一次性出现 —— 流式等于白做。
 *
 * <h3>为什么不能用 JSON 解析</h3>
 * arguments 在流式过程中<b>始终是残缺的</b>：`{"content":"午后` 不是一个合法 JSON。
 * 所以只能自己扫字符、维护状态机，而不是等收完再解析。
 */
final class ToolArgumentExtractor {

    /** 要抽取的字段名，例如 content */
    private final String field;
    /** JSON 里的键写法："content" */
    private final String key;

    private final StringBuilder buffer = new StringBuilder();
    private int cursor = 0;
    private boolean inValue = false;
    private boolean finished = false;

    ToolArgumentExtractor(String field) {
        this.field = field;
        this.key = "\"" + field + "\"";
    }

    /**
     * 追加新到达的分片。
     *
     * @return 本次新解析出的内容（可能为空串，表示这一片里没有新增的正文）
     */
    String append(String chunk) {
        if (chunk == null || chunk.isEmpty() || finished) {
            return "";
        }
        buffer.append(chunk);

        StringBuilder out = new StringBuilder();
        while (cursor < buffer.length()) {
            if (!inValue) {
                int found = buffer.indexOf(key, cursor);
                if (found < 0) {
                    // 没找到字段开头。但要留一点余量：字段名可能被切在两片之间，
                    // 直接跳到末尾会把半个 "conten 丢掉，下一片就永远拼不上了
                    cursor = Math.max(cursor, buffer.length() - key.length());
                    break;
                }
                // 冒号前后的空白要容忍：模型输出的 JSON 不保证是紧凑的
                int after = skipWhitespace(found + key.length());
                if (after >= buffer.length()) {
                    // 这一片到此为止，下一片接着从字段名处判断
                    cursor = found;
                    break;
                }
                if (buffer.charAt(after) != ':') {
                    // 同名的普通字符串值，不是字段：跳过继续找
                    cursor = after + 1;
                    continue;
                }
                after = skipWhitespace(after + 1);
                if (after >= buffer.length()) {
                    cursor = found;
                    break;
                }
                if (buffer.charAt(after) != '"') {
                    // 冒号后面不是字符串（可能是对象/数组），不是我们要的字段
                    cursor = after + 1;
                    continue;
                }
                cursor = after + 1;
                inValue = true;
            }

            // 进了值里，但这一片可能刚好停在值的开头 —— 必须先判边界再取字符，
            // 否则 "content":" 正好切在片尾时会下标越界
            if (cursor >= buffer.length()) {
                break;
            }

            char c = buffer.charAt(cursor);
            if (c == '\\') {
                // 转义序列可能只到了一半，等下一片
                if (cursor + 1 >= buffer.length()) {
                    break;
                }
                char escaped = buffer.charAt(cursor + 1);
                if (escaped == 'u') {
                    // 反斜杠 + u + 4 位十六进制。注意注释里不能直接写那个转义序列本身 ——
                    // Java 的词法器在注释里也会处理它，写出来就编译不过
                    if (cursor + 6 > buffer.length()) {
                        break;
                    }
                    try {
                        out.append((char) Integer.parseInt(buffer.substring(cursor + 2, cursor + 6), 16));
                        cursor += 6;
                        continue;
                    } catch (NumberFormatException e) {
                        // 不是合法的十六进制转义：按普通字符处理，别让整段卡住
                        out.append(escaped);
                        cursor += 2;
                        continue;
                    }
                }
                out.append(unescape(escaped));
                cursor += 2;
                continue;
            }
            if (c == '"') {
                // 值结束
                inValue = false;
                finished = true;
                cursor++;
                break;
            }
            out.append(c);
            cursor++;
        }
        return out.toString();
    }

    private int skipWhitespace(int from) {
        int index = from;
        while (index < buffer.length() && Character.isWhitespace(buffer.charAt(index))) {
            index++;
        }
        return index;
    }

    private char unescape(char escaped) {
        return switch (escaped) {
            case 'n' -> '\n';
            case 't' -> '\t';
            case 'r' -> '\r';
            case 'b' -> '\b';
            case 'f' -> '\f';
            // \" \\ \/ 以及其它：去掉反斜杠即可
            default -> escaped;
        };
    }
}
