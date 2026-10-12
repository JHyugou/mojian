package com.mojian.generation.llm;

import com.mojian.settings.service.LlmSettingsService;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型客户端路由器（FR-019）：
 * 生成链路统一面对 LlmClient，本类在每次调用前用极小的代价确认当前生效配置
 * （设置页 DB 配置 → 环境变量 → 内置模拟），配置变化时自动切换底层客户端。
 *
 * - 权威配置在数据库 llm_settings，本类缓存仅供去重，切换即时生效、无需重启；
 * - 正在进行的生成持有旧客户端引用继续完成；切换后旧流式看门狗随 retire 关闭；
 * - 测试接缝：既有用例通过 (MockLlmClient) 强转植入故障/读取提示词，
 *   这里对 Mock 生效期间做同名方法透传，保持全部旧测试无需感知路由层。
 */
@Slf4j
public class RoutingLlmClient implements LlmClient {

    private final LlmProperties properties;
    private final LlmSettingsService settingsService;

    private final AtomicReference<Cached> cache = new AtomicReference<>(new Cached("init", null));

    private record Cached(String fingerprint, LlmClient delegate) {
    }

    public RoutingLlmClient(LlmProperties properties, LlmSettingsService settingsService) {
        this.properties = properties;
        this.settingsService = settingsService;
    }

    @Override
    public String chat(LlmRequest request) {
        return active().chat(request);
    }

    @Override
    public void chatStream(LlmRequest request, StreamCallback callback) {
        active().chatStream(request, callback);
    }

    /** 当前激活客户端描述（诊断/日志/测试断言用，不含 Key 明文） */
    public String activeDescription() {
        return describe(active());
    }

    private LlmClient active() {
        LlmSettingsService.Effective effective = settingsService.effective();
        String fingerprint = effective == null ? "DEMO"
                : effective.source() + "|" + effective.baseUrl() + "|" + effective.model() + "|" + effective.apiKey();
        Cached cached = cache.get();
        if (cached.fingerprint().equals(fingerprint) && cached.delegate() != null) {
            return cached.delegate();
        }
        LlmClient next = effective == null ? new MockLlmClient()
                : new OpenAiCompatLlmClient(withEffective(effective));
        log.info("LLM 客户端切换: {} -> {}", describe(cached.delegate()), describe(next));
        retire(cached.delegate());
        cache.set(new Cached(fingerprint, next));
        return next;
    }

    /** 以生效配置克隆环境超时参数；界面配置始终走真实调用（mock=false） */
    private LlmProperties withEffective(LlmSettingsService.Effective effective) {
        LlmProperties clone = new LlmProperties();
        clone.setBaseUrl(effective.baseUrl());
        clone.setApiKey(effective.apiKey());
        clone.setModel(effective.model());
        clone.setMock(false);
        clone.setConnectTimeoutSeconds(properties.getConnectTimeoutSeconds());
        clone.setReadTimeoutSeconds(properties.getReadTimeoutSeconds());
        return clone;
    }

    private void retire(LlmClient old) {
        if (old instanceof OpenAiCompatLlmClient openAi) {
            try {
                openAi.shutdown();
            } catch (Exception e) {
                log.warn("退役模型客户端关闭失败: {}", e.getMessage());
            }
        }
    }

    private String describe(LlmClient client) {
        if (client == null) {
            return "未初始化";
        }
        if (client instanceof MockLlmClient) {
            return "Mock（演示模式）";
        }
        if (client instanceof OpenAiCompatLlmClient) {
            LlmSettingsService.Effective e = settingsService.effective();
            return e == null ? "OpenAI兼容" : "OpenAI兼容[" + e.baseUrl() + " / " + e.model() + "]";
        }
        return client.getClass().getSimpleName();
    }

    // ==================== 测试接缝（仅 Mock 生效期间有意义） ====================

    public void setFailNext(int n) {
        if (active() instanceof MockLlmClient mock) {
            mock.setFailNext(n);
        }
    }

    public String getLastUserPrompt() {
        return active() instanceof MockLlmClient mock ? mock.getLastUserPrompt() : null;
    }
}
