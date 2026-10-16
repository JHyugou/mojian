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
 * <p>本类<b>不做流式</b>：Agent 循环每一步都要等完整的 tool_calls 才能决定下一步，
 * 流式反而添乱。等最后给用户看的那一次输出再考虑流式。
 */
@Slf4j
public class OpenAiCompatAgentLlmClient implements AgentLlmClient {

    static final ObjectMapper MAPPER = new ObjectMapper();

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

        HttpRequest request = HttpRequest.newBuilder()
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
                        buildBody(resolved.model(), resolved.temperature(), messages, availableTools),
                        StandardCharsets.UTF_8))
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
            // （400 可能是"工具名不合法"或"超上下文"，402 是"余额/订阅问题"，责任和修法完全不同）
            String detail = shorten(response.body());
            log.warn("Agent 模型返回异常状态: {} (角色 {} / 模型 {}) body={}",
                    response.statusCode(), resolved.agentRole(), resolved.model(), detail);

            // 顺手把真实上下文上限学回来 —— 换模型就不用改代码了
            ContextLimitParser.parse(response.body()).ifPresent(limit -> {
                try {
                    profileService.learnContextLimit(resolved.agentRole(), limit);
                } catch (Exception e) {
                    log.warn("写回上下文上限失败（不影响本次报错）: {}", e.getMessage());
                }
            });

            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED,
                    "模型服务返回异常状态码 " + response.statusCode() + "：" + detail);
        }

        AgentLlmResponse parsed = parseResponse(response.body());
        log.debug("[{}] {} -> {}", resolved.agentRole(), resolved.model(), parsed.usage().describe());
        return parsed;
    }

    // ==================== 协议转换（可独立测试，不需要联网） ====================

    /**
     * 不指定温度的重载：不传时请求体里不带 {@code temperature} 字段，由服务方用默认值。
     * 生产调用一律走带温度的版本（角色配置里配了温度），这个重载主要给协议转换测试用。
     */
    static String buildBody(String model, List<AgentMessage> messages, List<AgentTool> tools) {
        return buildBody(model, null, messages, tools);
    }

    /**
     * 把内部消息模型翻译成 OpenAI 的请求体。
     * 这里的每一处细节都很容易错，所以单独抽出来做单元测试。
     */
    static String buildBody(String model, Double temperature,
                            List<AgentMessage> messages, List<AgentTool> tools) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", model);
        body.put("stream", false);
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
