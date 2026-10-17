package com.mojian.settings.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型目录（来自 models.dev）。
 *
 * <h3>为什么用它，而不是挨个问服务商</h3>
 * 最初想的是"向服务商要 {@code GET /models}"，实测发现<b>不是每家都有</b> ——
 * OpenCode Go 直接返回 404。后来从 OpenCode 自己的文档里找到答案：
 * 它的模型列表来自 <a href="https://models.dev">models.dev</a> 这个公开目录。
 * 一个请求就能拿到 232 家服务商、每家完整的模型清单。
 *
 * <h3>顺带解决了两件一直靠手工的事</h3>
 * 目录里每个模型都带 <b>上下文长度</b>与<b>定价</b> ——
 * 这两样之前都是我逐个实测、写死在代码里的（见 ModelContextWindow、
 * ModelPricing）。有了目录，将来可以自动填。
 *
 * <h3>为什么只留需要的字段</h3>
 * 原始 JSON 有 5.6MB，整棵树解析出来几十兆。这里只挑走
 * "某家的 api 地址 → 它的模型 id / 上下文 / 价格"，其余当场丢掉。
 */
@Slf4j
@Service
public class ModelsDevCatalog {

    private static final String CATALOG_URL = "https://models.dev/api.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 缓存多久刷新一次。模型上下线不频繁，一天足够 */
    private static final Duration TTL = Duration.ofHours(24);

    /**
     * @param id            模型 id（调用时填的那个）
     * @param name          显示名
     * @param contextLimit  上下文长度；目录没给就是 null
     * @param inputCost     每百万 token 输入价（美元）；没给就是 null
     * @param outputCost    每百万 token 输出价（美元）
     */
    public record ModelInfo(String id, String name, Integer contextLimit,
                            Double inputCost, Double outputCost) {
    }

    /** api 地址（已去掉结尾斜杠）→ 该地址下的模型 */
    private volatile Map<String, List<ModelInfo>> byApi = Map.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    /**
     * 启动后异步预热。
     *
     * <p>异步是必须的：这是个 5.6MB 的外网请求，放在启动路径上会让应用
     * 因为一个"锦上添花"的功能而慢十几秒。预热失败也不影响启动 ——
     * 用户点「获取可用模型」时还会再试一次。
     */
    @PostConstruct
    public void warmUp() {
        Thread thread = new Thread(this::refresh, "models-dev-warmup");
        thread.setDaemon(true);
        thread.start();
    }

    /** 目录是否已就绪 */
    public boolean ready() {
        return !byApi.isEmpty();
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    /**
     * 查某个接口地址下的可用模型。
     *
     * @return 匹配不到就返回空列表 —— 调用方会退回"问服务商"或"用预设里的"
     */
    public List<ModelInfo> modelsFor(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return List.of();
        }
        if (isStale()) {
            refresh();
        }
        return byApi.getOrDefault(normalize(baseUrl), List.of());
    }

    private boolean isStale() {
        return byApi.isEmpty() || Instant.now().isAfter(loadedAt.plus(TTL));
    }

    private String normalize(String baseUrl) {
        return baseUrl.trim().replaceAll("/+$", "");
    }

    /**
     * 拉取并重建缓存。
     *
     * <p>失败时保留旧数据：一份过期的模型列表也比空的好 ——
     * 至少常用模型还在里面，而"什么都没有"会让界面直接退化成手填。
     */
    public synchronized void refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(CATALOG_URL))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "mojian-agent/0.1")
                    .GET()
                    .build();

            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("拉取模型目录失败：HTTP {}", response.statusCode());
                return;
            }

            Map<String, List<ModelInfo>> parsed = parse(response.body());
            if (parsed.isEmpty()) {
                log.warn("模型目录解析后为空，保留原有缓存");
                return;
            }
            byApi = parsed;
            loadedAt = Instant.now();
            log.info("模型目录已更新：{} 个服务商地址，{} 个模型",
                    parsed.size(), parsed.values().stream().mapToInt(List::size).sum());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("拉取模型目录失败（保留旧缓存）: {}", e.getMessage());
        }
    }

    /** 只挑走需要的字段，其余当场丢掉 —— 原始 JSON 有 5.6MB */
    private Map<String, List<ModelInfo>> parse(String body) throws Exception {
        JsonNode root = MAPPER.readTree(body);
        Map<String, List<ModelInfo>> result = new LinkedHashMap<>();

        root.fields().forEachRemaining(provider -> {
            JsonNode node = provider.getValue();
            String api = node.path("api").asText("");
            JsonNode models = node.path("models");
            if (!StringUtils.hasText(api) || !models.isObject()) {
                return;
            }
            List<ModelInfo> list = new ArrayList<>();
            models.fields().forEachRemaining(entry -> {
                JsonNode model = entry.getValue();
                JsonNode limit = model.path("limit");
                JsonNode cost = model.path("cost");
                list.add(new ModelInfo(
                        entry.getKey(),
                        model.path("name").asText(entry.getKey()),
                        limit.hasNonNull("context") ? limit.get("context").asInt() : null,
                        cost.hasNonNull("input") ? cost.get("input").asDouble() : null,
                        cost.hasNonNull("output") ? cost.get("output").asDouble() : null));
            });
            if (!list.isEmpty()) {
                result.put(normalize(api), list);
            }
        });
        return result;
    }
}
