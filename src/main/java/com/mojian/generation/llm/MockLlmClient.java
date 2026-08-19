package com.mojian.generation.llm;

import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mock 模型客户端：无模型环境演示与自动化测试使用（FR-012）。
 *
 * 行为约定：
 * - 指令包含 FAIL_TRIGGER 时抛出模拟故障（验证失败路径与重试）；
 * - failNext(n) 让接下来 n 次请求失败（验证"失败后重试成功"）；
 * - 输出会嵌入提示词中的【章节标题】与【创作指令】摘要，便于人工验证上下文确实被使用。
 */
public class MockLlmClient implements LlmClient {

    public static final String FAIL_TRIGGER = "触发模拟失败";

    @Setter
    private volatile int failNext = 0;
    private final AtomicInteger callCounter = new AtomicInteger();

    @Override
    public String chat(LlmRequest request) {
        failIfNeeded();
        return switch (request.purpose()) {
            case "PLOT_PLAN" -> buildPlan(request);
            case "CHAPTER_MEMORY" -> buildMemory(request);
            default -> buildPlan(request);
        };
    }

    @Override
    public void chatStream(LlmRequest request, StreamCallback callback) {
        failIfNeeded();
        String text = switch (request.purpose()) {
            case "CONTENT", "CONTINUE" -> buildContent(request);
            default -> buildRewrite(request);
        };
        // 以 12 字为片段切分，模拟流式到达
        List<String> chunks = splitToChunks(text, 12);
        StringBuilder full = new StringBuilder();
        for (String chunk : chunks) {
            full.append(chunk);
            callback.onDelta(chunk);
        }
        callback.onComplete(full.toString());
    }

    private void failIfNeeded() {
        if (failNext > 0) {
            failNext = failNext - 1;
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "模拟模型故障（MockLlmClient）");
        }
    }

    private String buildPlan(LlmRequest request) {
        String title = extractMarker(request.userPrompt(), "章节标题");
        String instruction = extractMarker(request.userPrompt(), "创作指令");
        return """
                【剧情方案】%s

                一、开场：%s主角于旧局中起身，环境给出第一处不安的细节，读者疑问由此落地。
                二、推进：因%s引出一次正面冲突，次要人物被迫表态，矛盾由暗转明。
                三、转折：冲突落点反噬主角预期，出现一个必须当下决断的代价。
                四、收束：主角做出选择，留下未解钩子指向下一章，控制篇幅在两千五百字内。
                """.formatted(title == null ? "" : "（" + title + "）",
                instruction == null ? "" : "围绕「" + instruction + "」，",
                instruction == null ? "旧怨" : "「" + instruction + "」");
    }

    private String buildContent(LlmRequest request) {
        String title = extractMarker(request.userPrompt(), "章节标题");
        String plan = extractMarker(request.userPrompt(), "剧情方案");
        return """
                %s
                　　夜雨敲檐，灯芯爆了最后一朵灯花。他垂眼坐着，指腹压在刀镡的裂纹上，像在数一道旧年的伤。门外有脚步声过去，又停住，泥水从门槛缝里渗进来，蜿蜒如蛇。
                　　他想起白日里那句话——话不难听，难的是说话的人笑着。于是他抬手灭灯，屋里只剩雨声，和一点被雨声压住的呼吸。有人推门，门轴未响；他先动了。
                　　三招之内分不出胜负，却分出了去处。那人退出檐下，雨幕里只余一句："[%s]，你护不住。"他立于门内，雨水顺着刀尖滴成一线，答了两个字："留下。"
                　　天将亮时雨停了。檐角滴水，一声，又一声，像替谁数着日子。他收刀入鞘，转身看见桌上多了一物——那是本不该出现在此处的东西。
                """.formatted(title == null ? "" : "【" + title + "】",
                        plan == null ? "此地之约" : plan);
    }

    private String buildRewrite(LlmRequest request) {
        String selection = extractMarker(request.userPrompt(), "选中文本");
        return "【改写结果】" + (selection == null ? "" : selection) + "——以上段落经润饰：节奏收紧、意象归拢、对话留白增加，情绪由显转隐。";
    }

    private String buildMemory(LlmRequest request) {
        String title = extractMarker(request.userPrompt(), "章节标题");
        return """
                摘要：%s推进主线冲突，主角做出关键抉择并付出代价。
                重要事实：新增关键道具一件；次要人物立场明朗化。
                未解决冲突：神秘来客身份未明；桌上异物的来历成谜。
                人物状态变化：主角对盟友的信任出现裂痕。
                """.formatted(title == null ? "本章" : "「" + title + "」");
    }

    private String extractMarker(String prompt, String marker) {
        int idx = prompt.indexOf("【" + marker + "】");
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length() + 2;
        int end = prompt.indexOf('\n', start);
        String value = end < 0 ? prompt.substring(start) : prompt.substring(start, end);
        value = value.trim();
        return value.isEmpty() ? null : (value.length() > 60 ? value.substring(0, 60) : value);
    }

    private List<String> splitToChunks(String text, int size) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            chunks.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return chunks;
    }
}
