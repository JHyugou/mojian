package com.mojian.generation.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OpenAI 兼容接口客户端（/chat/completions）。
 *
 * 设计要点：
 * - 基于 JDK HttpClient，零额外依赖；同步请求用整段超时，流式请求用"空闲看门狗"：
 *   超过 readTimeoutSeconds 无新数据则中断读取线程，任务转 FAILED（SRS 7.3）。
 * - API Key 只进入 Authorization 请求头，不进入日志、快照或异常信息（SRS 7.1）。
 */
@Slf4j
public class OpenAiCompatLlmClient implements LlmClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmProperties properties;
    private final HttpClient httpClient;
    private final ScheduledExecutorService watchdog;

    public OpenAiCompatLlmClient(LlmProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getConnectTimeoutSeconds()))
                .build();
        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "llm-stream-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public String chat(LlmRequest request) {
        HttpRequest httpRequest = buildRequest(request, false);
        HttpResponse<String> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "模型服务连接失败，请稍后重试");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("模型服务返回异常状态: {} (模型: {})", response.statusCode(), properties.getModel());
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "模型服务返回异常状态码 " + response.statusCode());
        }
        try {
            JsonNode root = MAPPER.readTree(response.body());
            return root.path("choices").path(0).path("message").path("content").asText("");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "模型返回格式异常");
        }
    }

    @Override
    public void chatStream(LlmRequest request, StreamCallback callback) {
        HttpRequest httpRequest = buildRequest(request, true);
        AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        Thread worker = Thread.currentThread();
        StringBuilder full = new StringBuilder();

        ScheduledFuture<?> guard = watchdog.scheduleAtFixedRate(() -> {
            long idleMillis = System.currentTimeMillis() - lastActivity.get();
            if (idleMillis > properties.getReadTimeoutSeconds() * 1000L) {
                log.warn("模型流式输出空闲超过 {} 秒，中断读取", properties.getReadTimeoutSeconds());
                worker.interrupt();
            }
        }, 1, 1, TimeUnit.SECONDS);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream()).body(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("流式输出读取超时中断");
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                lastActivity.set(System.currentTimeMillis());
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) {
                    break;
                }
                String reasoning = extractReasoning(payload);
                if (!reasoning.isEmpty()) {
                    callback.onReasoning(reasoning);
                }
                String delta = extractDelta(payload);
                if (!delta.isEmpty()) {
                    full.append(delta);
                    callback.onDelta(delta);
                }
            }
            guard.cancel(false);
            callback.onComplete(full.toString());
        } catch (InterruptedException e) {
            guard.cancel(false);
            Thread.currentThread().interrupt();
            callback.onError(new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "模型流式输出超时，请重试"));
        } catch (Exception e) {
            guard.cancel(false);
            log.warn("模型流式输出失败: {}", e.getMessage());
            callback.onError(new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "模型流式输出失败，请稍后重试"));
        }
    }

    /** 停止流式空闲看门狗；路由层切换配置退役旧客户端时调用 */
    @Override
    public void shutdown() {
        watchdog.shutdownNow();
    }

    private HttpRequest buildRequest(LlmRequest request, boolean stream) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", properties.getModel());
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.systemPrompt());
        messages.addObject().put("role", "user").put("content", request.userPrompt());

        return HttpRequest.newBuilder()
                .uri(URI.create(trimSlash(properties.getBaseUrl()) + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.getReadTimeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private String extractDelta(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            return node.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    /** 思维链增量：GLM/DeepSeek 等推理模型在 delta.reasoning_content 输出思考过程 */
    private String extractReasoning(String payload) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            return node.path("choices").path(0).path("delta").path("reasoning_content").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
