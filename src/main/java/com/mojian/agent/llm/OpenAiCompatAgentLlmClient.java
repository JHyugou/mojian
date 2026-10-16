package com.mojian.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mojian.agent.AgentContext;
import com.mojian.agent.context.ContextLimitParser;
import com.mojian.agent.service.AgentProfileService;
import com.mojian.agent.tool.AgentTool;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.llm.LlmProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 带工具调用能力的 OpenAI 兼容客户端（/chat/completions）。
 *
 * <p>和墨笺原有的 {@code OpenAiCompatLlmClient} 相比，多了三件事：
 * <ol>
 *   <li>请求体里带 {@code tools} 字段 —— 这就是"给模型的说明书"</li>
 *   <li>解析响应里的 {@code tool_calls} —— 模型返回的是"我想调哪个工具、参数是什么"，
 *       不是执行结果</li>
 *   <li>解析 {@code usage} —— 让"这次花了多少"可测量</li>
 * </ol>
 *
 * <p><b>按角色取配置</b>：模型名、温度、接口地址、密钥全部来自 {@link AgentProfileService}，
 * 而角色存在 {@link AgentContext#agentRole()} 里。于是"主 Agent 用便宜模型规划、
 * 副 Agent 用强模型写作"是由数据决定的，不是写死在代码里的。
 *
 * <p><b>超限自动学习</b>：服务方拒绝超限请求时会把真实上限写在错误里，
 * 这里顺手解析出来写回配置 —— 用户换模型不需要改任何代码。
 *
 * <p><b>支持流式，但它不是必需能力</b>：Agent 循环每一步都要等完整的 tool_calls 才能决定下一步，
 * 所以流式在这里的唯一价值是"让用户边等边看见"。
 *
 * <p>而这个价值很大：写作任务一轮要两三分钟，且几乎全部时间都花在
 * <b>生成 tool_calls 的参数</b>上（正文就在那串 JSON 里）。不流式的话，
 * 用户要盯着空白干等两分钟才看到第一个字节 —— 那才是真正的问题。
 * 拿不到流式就自动退回非流式：功能不受影响，只是要多等一会儿。
 */
@Slf4j
public class OpenAiCompatAgentLlmClient implements AgentLlmClient {

    static final ObjectMapper MAPPER = new ObjectMapper();

    /** 连接层失败的重试次数。这类失败几乎都是瞬时的，重试往往就好 */
    private static final int CONNECT_RETRY_TIMES = 3;
    /** 退避基数：第 n 次重试前等 n × 这个毫秒数 */
    private static final long RETRY_BACKOFF_MILLIS = 1_500;

    private final LlmProperties properties;
    private final AgentProfileService profileService;
    private final HttpClient httpClient;

    public OpenAiCompatAgentLlmClient(LlmProperties properties, AgentProfileService profileService) {
        this.properties = properties;
        this.profileService = profileService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .build();
    }

    @Override
    public AgentLlmResponse chat(AgentContext context, List<AgentMessage> messages, List<AgentTool> availableTools) {
        AgentProfileService.ResolvedModel resolved = profileService.resolve(context.agentRole());

        HttpRequest request = buildRequest(context, resolved, messages, availableTools, false);
        HttpResponse<String> response = sendWithRetry(request);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 必须把服务方返回的内容带上：只报一个状态码，排查时只能靠猜
            // （400 可能是"工具名不合法"或"超上下文"，402 是"余额/订阅问题"，责任和修法完全不同）
            failWithStatus(response.statusCode(), resolved, response.body());
        }

        AgentLlmResponse parsed = parseResponse(response.body());
        log.debug("[{}] {} -> {}", resolved.agentRole(), resolved.model(), parsed.usage().describe());
        return parsed;
    }

    @Override
    public AgentLlmResponse chat(AgentContext context, List<AgentMessage> messages,
                                 List<AgentTool> availableTools, LlmStreamListener listener) {
        AgentProfileService.ResolvedModel resolved = profileService.resolve(context.agentRole());
        HttpRequest request = buildRequest(context, resolved, messages, availableTools, true);

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.warn("Agent 流式连接失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED,
                    "连不上模型服务：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "模型调用被中断");
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 错误路径要把 body 读出来才能给出有用的信息（状态码本身说明不了什么）
            failWithStatus(response.statusCode(), resolved, readQuietly(response.body()));
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            AgentLlmResponse parsed = parseStream(reader, listener);
            log.debug("[{}] {} 流式 -> {}", resolved.agentRole(), resolved.model(), parsed.usage().describe());
            return parsed;
        } catch (IOException e) {
            log.warn("Agent 流式读取中断: {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED,
                    "模型流式输出中断：" + e.getMessage());
        }
    }

    /**
     * 解析 SSE 流，边解析边回调。
     *
     * <p>两个容易踩的点：
     * <ol>
     *   <li><b>tool_calls 是分片到达的</b>：第一片给 id 和工具名，后续片一点点追加
     *       arguments 字符串。必须按 {@code index} 归并，拼错一步整个参数就是坏的</li>
     *   <li><b>脏帧要跳过而不是让整轮失败</b>：流里偶尔混进不完整的 JSON 帧，
     *       因为一帧坏掉就丢弃已经生成的几百字，代价太大</li>
     * </ol>
     */
    private AgentLlmResponse parseStream(BufferedReader reader, LlmStreamListener listener) throws IOException {
        StringBuilder content = new StringBuilder();
        Map<Integer, ToolCallBuilder> callBuilders = new LinkedHashMap<>();
        TokenUsage usage = TokenUsage.NONE;
        boolean toolCallNotified = false;

        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }

            JsonNode node;
            try {
                node = MAPPER.readTree(payload);
            } catch (Exception e) {
                continue; // 脏帧跳过，不拖垮整轮
            }

            JsonNode usageNode = node.path("usage");
            if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                usage = parseUsage(node);
            }

            JsonNode delta = node.path("choices").path(0).path("delta");
            String reasoning = delta.path("reasoning_content").asText("");
            if (!reasoning.isEmpty()) {
                listener.onReasoning(reasoning);
            }
            String text = delta.path("content").asText("");
            if (!text.isEmpty()) {
                content.append(text);
                listener.onDelta(text);
            }

            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode call : toolCalls) {
                    if (!toolCallNotified) {
                        listener.onToolCallStarted();
                        toolCallNotified = true;
                    }
                    int index = call.path("index").asInt(0);
                    ToolCallBuilder builder = callBuilders.computeIfAbsent(index, k -> new ToolCallBuilder());
                    boolean gotId = call.hasNonNull("id");
                    if (gotId) {
                        builder.id = call.path("id").asText("");
                    }
                    JsonNode function = call.path("function");
                    boolean gotName = function.hasNonNull("name");
                    if (gotName) {
                        builder.name = function.path("name").asText("");
                    }
                    if (function.hasNonNull("arguments")) {
                        builder.arguments.append(function.path("arguments").asText(""));
                    }
                    // 诊断用：收到 tool_calls 帧却没有 id / 函数名，说明这个服务方的
                    // 流式结构和我们预期的不一样。把原始帧打出来，下次一跑就能定位 ——
                    // 否则只能看到"工具名是空的"这种毫无线索的现象
                    if (!gotId && !gotName) {
                        log.warn("流式 tool_calls 帧结构异常（无 id 无函数名），原始帧: {}", shorten(payload));
                    }
                }
            }
        }

        List<AgentToolCall> toolCalls = callBuilders.values().stream()
                .map(b -> new AgentToolCall(b.id, b.name,
                        b.arguments.isEmpty() ? "{}" : b.arguments.toString()))
                .toList();
        // 兜底自检：解析出工具调用却没有名字，等于这轮白跑（会被当成"工具不存在"回传，
        // 模型只能反复重试）。明确报出来，别让它伪装成"模型乱调工具"
        for (AgentToolCall call : toolCalls) {
            if (call.name() == null || call.name().isBlank()) {
                log.warn("流式解析得到一个没有名字的工具调用（id={}），本轮无法执行", call.id());
            }
        }
        return new AgentLlmResponse(content.isEmpty() ? null : content.toString(), toolCalls, usage);
    }

    /** 流式分片里的一个工具调用：id 和名字先到，arguments 一片片追加 */
    private static final class ToolCallBuilder {
        private String id = "";
        private String name = "";
        private final StringBuilder arguments = new StringBuilder();
    }

    private HttpRequest buildRequest(AgentContext context, AgentProfileService.ResolvedModel resolved,
                                     List<AgentMessage> messages, List<AgentTool> availableTools,
                                     boolean stream) {
        return HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(resolved.baseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + resolved.apiKey())
                // 会话标识：服务方要求必带（缺了会 400），同时用于 prompt 缓存路由 ——
                // 命中缓存后读取单价只有未命中的几十分之一，所以这个头直接关系到账单
                .header("x-opencode-session", context.sessionId())
                // 自报家门（服务方明确要求客户端用自己的 UA，而不是通用 SDK 的名字）
                .header("User-Agent", "mojian-agent/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildBody(resolved.model(), resolved.temperature(), messages, availableTools, stream),
                        StandardCharsets.UTF_8))
                .build();
    }

    /** 统一的错误处理：日志 + 学习上下文上限 + 抛出可读异常 */
    private void failWithStatus(int statusCode, AgentProfileService.ResolvedModel resolved, String body) {
        String detail = shorten(body);
        log.warn("Agent 模型返回异常状态: {} (角色 {} / 模型 {}) body={}",
                statusCode, resolved.agentRole(), resolved.model(), detail);

        ContextLimitParser.parse(body).ifPresent(limit -> {
            try {
                profileService.learnContextLimit(resolved.agentRole(), limit);
            } catch (Exception e) {
                log.warn("写回上下文上限失败（不影响本次报错）: {}", e.getMessage());
            }
        });

        throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED,
                "模型服务返回异常状态码 " + statusCode + "：" + detail);
    }

    private String readQuietly(InputStream stream) {
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    // ==================== 连接层重试 ====================

    /**
     * 发送请求，对<b>连接层</b>故障做重试。
     *
     * <p>为什么只重试连接层：HTTP 状态码（400/402/429）是服务方明确表达了意图，
     * 重试没有意义、甚至有害（对 429 重试只会更糟）；
     * 而"握手中断""读到一半 EOF""连接超时"是瞬时故障，重试往往就好了。
     *
     * <p>这条不是理论：真实排查中遇到的两次失败
     * （{@code EOF reached while reading}、{@code Remote host terminated the handshake}）
     * 都属于这一类，而且当时服务方对 <b>最小请求</b>也同样连不上。
     * 对用户来说，这类重试应该是完全无感的。
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request) {
        IOException last = null;
        for (int attempt = 1; attempt <= CONNECT_RETRY_TIMES; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                last = e;
                log.warn("Agent 模型连接失败（第 {}/{} 次）: {}",
                        attempt, CONNECT_RETRY_TIMES, e.getMessage());
                if (attempt < CONNECT_RETRY_TIMES) {
                    sleepQuietly(RETRY_BACKOFF_MILLIS * attempt);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "模型调用被中断");
            }
        }
        throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED,
                "连不上模型服务（已重试 " + CONNECT_RETRY_TIMES + " 次）："
                        + (last == null ? "未知错误" : last.getMessage()));
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 协议转换（可独立测试，不需要联网） ====================

    /**
     * 不指定温度的重载：不传时请求体里不带 {@code temperature} 字段，由服务方用默认值。
     * 生产调用一律走带温度的版本（角色配置里配了温度），这个重载主要给协议转换测试用。
     */
    static String buildBody(String model, List<AgentMessage> messages, List<AgentTool> tools) {
        return buildBody(model, null, messages, tools, false);
    }

    static String buildBody(String model, Double temperature,
                            List<AgentMessage> messages, List<AgentTool> tools) {
        return buildBody(model, temperature, messages, tools, false);
    }

    /**
     * 把内部消息模型翻译成 OpenAI 的请求体。
     * 这里的每一处细节都很容易错，所以单独抽出来做单元测试。
     */
    static String buildBody(String model, Double temperature,
                            List<AgentMessage> messages, List<AgentTool> tools, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);
        if (stream) {
            // 流式响应默认不含 usage，要显式索取；服务方不支持时会忽略这个字段，
            // 那时用量记为 0（不准确但不影响功能）
            body.putObject("stream_options").put("include_usage", true);
        }
        if (temperature != null) {
            body.put("temperature", temperature);
        }

        ArrayNode messageArray = body.putArray("messages");
        for (AgentMessage message : messages) {
            ObjectNode node = messageArray.addObject();
            node.put("role", message.role());
            if (message.content() != null) {
                node.put("content", message.content());
            }
            if (message.toolCallId() != null) {
                // 工具结果必须带 tool_call_id，否则模型对不上这是哪次调用的结果
                node.put("tool_call_id", message.toolCallId());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode callArray = node.putArray("tool_calls");
                for (AgentToolCall call : message.toolCalls()) {
                    ObjectNode callNode = callArray.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments() == null ? "{}" : call.arguments());
                }
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = body.putArray("tools");
            for (AgentTool tool : tools) {
                ObjectNode toolNode = toolArray.addObject();
                toolNode.put("type", "function");
                ObjectNode function = toolNode.putObject("function");
                function.put("name", tool.name());
                function.put("description", tool.description());
                function.set("parameters", MAPPER.valueToTree(tool.parametersSchema()));
            }
        }
        return body.toString();
    }

    /**
     * 解析响应。只有两种形态：
     * <ul>
     *   <li>有 tool_calls → 模型要求调工具（此时 content 通常为 null）</li>
     *   <li>没有 tool_calls → 模型直接给了答案</li>
     * </ul>
     * 同时解析 {@code usage}；部分服务方不返回用量，此时按 0 计而不是报错。
     */
    static AgentLlmResponse parseResponse(String responseBody) {
        try {
            JsonNode root = MAPPER.readTree(responseBody);
            JsonNode message = root.path("choices").path(0).path("message");
            String content = message.path("content").asText(null);

            List<AgentToolCall> calls = new ArrayList<>();
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode node : toolCalls) {
                    JsonNode function = node.path("function");
                    calls.add(new AgentToolCall(
                            node.path("id").asText(""),
                            function.path("name").asText(""),
                            function.path("arguments").asText("{}")));
                }
            }

            return new AgentLlmResponse(content, calls, parseUsage(root));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "模型返回格式异常");
        }
    }

    /** OpenAI 语义：prompt_tokens 是输入总量，内含的 cached_tokens 是命中缓存的那部分 */
    private static TokenUsage parseUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        if (usage.isMissingNode()) {
            return TokenUsage.NONE;
        }
        return new TokenUsage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("prompt_tokens_details").path("cached_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0));
    }

    private String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** 错误详情只留前 300 字，避免把超长响应灌进日志和界面 */
    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }
}
