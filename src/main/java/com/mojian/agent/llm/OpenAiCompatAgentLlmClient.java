package com.mojian.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mojian.agent.AgentContext;
import com.mojian.agent.tool.AgentTool;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.llm.LlmProperties;
import com.mojian.settings.service.LlmSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 带工具调用能力的 OpenAI 兼容客户端（/chat/completions）。
 *
 * <p>和墨笺原有的 {@code OpenAiCompatLlmClient} 相比，多了两件事：
 * <ol>
 *   <li>请求体里带 {@code tools} 字段 —— 这就是"给模型的说明书"</li>
 *   <li>解析响应里的 {@code tool_calls} —— 模型返回的是"我想调哪个工具、参数是什么"，
 *       不是执行结果</li>
 * </ol>
 *
 * <p>配置复用墨笺现有的裁决链路（设置页 → 环境变量 → 演示模式），
 * 所以设置页改了配置这里立刻生效，不用重启。
 *
 * <p>本类<b>不做流式</b>：Agent 循环每一步都要等完整的 tool_calls 才能决定下一步，
 * 流式反而添乱。等最后给用户看的那一次输出再考虑流式。
 */
@Slf4j
public class OpenAiCompatAgentLlmClient implements AgentLlmClient {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmProperties properties;
    private final LlmSettingsService settingsService;
    private final HttpClient httpClient;

    public OpenAiCompatAgentLlmClient(LlmProperties properties, LlmSettingsService settingsService) {
        this.properties = properties;
        this.settingsService = settingsService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .build();
    }

    @Override
    public AgentLlmResponse chat(AgentContext context, List<AgentMessage> messages, List<AgentTool> availableTools) {
        LlmSettingsService.Effective effective = settingsService.effective();
        if (effective == null || !StringUtils.hasText(effective.baseUrl())) {
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED,
                    "还没有配置模型：请先在设置页填写接口地址、密钥与模型名");
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(effective.baseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + effective.apiKey())
                // 会话标识：服务方据此做 prompt 缓存路由。命中缓存后的读取单价约为未命中的几十分之一，
                // 所以这个头直接关系到账单
                .header("x-opencode-session", context.sessionId())
                // 自报家门（服务方明确要求客户端用自己的 UA，而不是通用 SDK 的名字）
                .header("User-Agent", "mojian-agent/0.1")
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildBody(effective.model(), messages, availableTools), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Agent 模型连接失败: {}", e.getMessage());
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "模型服务连接失败，请稍后重试");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 必须把服务方返回的内容带上：只报一个状态码，排查时只能靠猜
            // （例如 400 可能是"工具名不合法"，402 是"余额/订阅问题"，责任和修法完全不同）
            String detail = shorten(response.body());
            log.warn("Agent 模型返回异常状态: {} (模型: {}) body={}", response.statusCode(), effective.model(), detail);
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED,
                    "模型服务返回异常状态码 " + response.statusCode() + "：" + detail);
        }
        return parseResponse(response.body());
    }

    // ==================== 协议转换（可独立测试，不需要联网） ====================

    /**
     * 把内部消息模型翻译成 OpenAI 的请求体。
     * 这里的每一处细节都很容易错，所以单独抽出来做单元测试。
     */
    static String buildBody(String model, List<AgentMessage> messages, List<AgentTool> tools) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", false);

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
     */
    static AgentLlmResponse parseResponse(String responseBody) {
        try {
            JsonNode message = MAPPER.readTree(responseBody)
                    .path("choices").path(0).path("message");
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
            return new AgentLlmResponse(content, calls);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "模型返回格式异常");
        }
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
