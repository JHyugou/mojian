package com.mojian.settings.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.crypto.LocalSecretCipher;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.llm.LlmProperties;
import com.mojian.settings.dto.LlmSettingsSaveRequest;
import com.mojian.settings.entity.LlmSettings;
import com.mojian.settings.llm.LlmProviderPreset;
import com.mojian.settings.llm.ModelsDevCatalog;
import com.mojian.settings.mapper.LlmSettingsMapper;
import com.mojian.settings.vo.LlmModelVO;
import com.mojian.settings.vo.LlmSettingsStatusVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
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
 * 模型接入设置服务：设置页配置的存取与"当前生效模型配置"的唯一裁决点。
 *
 * 生效优先级（FR-019）：
 *   设置页配置（DB，enabled 且三项齐全） → 服务端环境变量 → 内置模拟模式。
 *
 * 安全约束：
 * - API Key 明文只在这里与调用瞬间存在于内存；落库为 AES-GCM 密文；
 * - 任何日志/返回值/快照不出现完整 Key，对外只有掩码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmSettingsService {

    /** 全局唯一配置行 id */
    public static final long SINGLETON_ID = 1L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ENSURE_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS llm_settings (
                id              BIGINT       NOT NULL COMMENT '主键（固定为1）',
                base_url        VARCHAR(300) DEFAULT NULL COMMENT 'OpenAI兼容接口地址',
                api_key_cipher  VARCHAR(600) DEFAULT NULL COMMENT 'API密钥密文（本机密钥文件AES-GCM加密）',
                model           VARCHAR(100) DEFAULT NULL COMMENT '模型名称',
                enabled_models  TEXT         DEFAULT NULL COMMENT '用户启用的模型id（JSON数组）；空=全部启用',
                enabled         TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1启用 0停用',
                created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '模型接入设置'
            """;

    private final LlmSettingsMapper mapper;
    private final LocalSecretCipher cipher;
    private final LlmProperties llmProperties;
    private final JdbcTemplate jdbcTemplate;
    private final ModelsDevCatalog catalog;

    /** 老库升级友好：启动时幂等建表，无需手动重跑 schema.sql */
    @PostConstruct
    public void ensureTable() {
        jdbcTemplate.execute(ENSURE_TABLE_DDL);
        // 老库没有这一列：CREATE TABLE IF NOT EXISTS 对已存在的表什么都不做
        if (!columnExists("llm_settings", "enabled_models")) {
            jdbcTemplate.execute(
                    "ALTER TABLE llm_settings ADD COLUMN enabled_models TEXT DEFAULT NULL "
                            + "COMMENT '用户启用的模型id（JSON数组）；空=全部启用' AFTER model");
            log.info("llm_settings.enabled_models 已自动补列");
        }
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }

    // ==================== 模型的启用/停用 ====================

    /**
     * 用户启用过的模型 id。
     *
     * <p>返回 {@code null} 表示"没有做过选择" —— 这与"选择了一个空的集合"
     * 语义不同：前者应当理解为<b>全部启用</b>（先让人看见全部，再一个个关掉），
     * 后者表示用户真的把所有模型都关了。
     */
    public java.util.Set<String> enabledModelIds() {
        LlmSettings row = mapper.selectSingleton();
        if (row == null || !StringUtils.hasText(row.getEnabledModels())) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(row.getEnabledModels());
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            if (node.isArray()) {
                node.forEach(item -> {
                    if (item.isTextual()) {
                        ids.add(item.asText());
                    }
                });
            }
            return ids;
        } catch (Exception e) {
            log.warn("解析 enabled_models 失败，按全部启用处理: {}", e.getMessage());
            return null;
        }
    }

    /** 保存启用列表；传空表示"全部启用" */
    public void saveEnabledModels(java.util.List<String> models) {
        LlmSettings row = mapper.selectSingleton();
        if (row == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请先保存模型接入配置");
        }
        String json = null;
        if (models != null && !models.isEmpty()) {
            try {
                json = MAPPER.writeValueAsString(models);
            } catch (Exception e) {
                throw new BusinessException(ErrorCode.PARAM_INVALID, "启用列表格式不正确");
            }
        }
        /*
         * 必须用显式 set 的 UpdateWrapper，不能用 updateById ——
         * MyBatis-Plus 的 updateById 默认跳过 null 字段，而"全部启用"恰恰就是写 null。
         * 用 updateById 的话，用户点了"全部启用"会静默无效，库里还是上一次的选择。
         * （这个坑在这个项目里踩过一次，又在同一个地方踩了第二次。）
         */
        mapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<LlmSettings>lambdaUpdate()
                .eq(LlmSettings::getId, SINGLETON_ID)
                .set(LlmSettings::getEnabledModels, json));
        log.info("已保存启用的模型：{}", json == null ? "（全部）" : models.size() + " 个");
    }

    /**
     * 当前生效的模型配置；null 表示应使用内置模拟模式。
     * 返回对象含 Key 明文，仅供生成链路内部使用，禁止写入日志或响应。
     */
    public Effective effective() {
        LlmSettings row = mapper.selectSingleton();
        if (row != null && Boolean.TRUE.equals(row.getEnabled())) {
            String plainKey = cipher.decryptSafe(row.getApiKeyCipher());
            if (StringUtils.hasText(row.getBaseUrl())
                    && StringUtils.hasText(row.getModel())
                    && StringUtils.hasText(plainKey)) {
                return new Effective(row.getBaseUrl(), plainKey, row.getModel(), "DB");
            }
        }
        if (!llmProperties.isMock() && StringUtils.hasText(llmProperties.getApiKey())) {
            return new Effective(llmProperties.getBaseUrl(), llmProperties.getApiKey(),
                    llmProperties.getModel(), "ENV");
        }
        return null;
    }

    /** 设置页状态视图：Key 仅掩码 */
    public LlmSettingsStatusVO status() {
        Effective e = effective();
        LlmSettingsStatusVO vo = new LlmSettingsStatusVO();
        if (e == null) {
            vo.setMode("DEMO");
            return vo;
        }
        vo.setMode("LIVE");
        vo.setSource(e.source());
        vo.setBaseUrl(e.baseUrl());
        vo.setModel(e.model());
        vo.setApiKeyMasked(mask(e.apiKey()));
        return vo;
    }

    /** 保存并启用界面配置（覆盖环境变量优先级） */
    public LlmSettingsStatusVO save(LlmSettingsSaveRequest request) {
        validate(request);
        LlmSettings row = mapper.selectSingleton();
        boolean create = row == null;
        if (create) {
            row = new LlmSettings();
            row.setId(SINGLETON_ID);
        }
        String newPlainKey = request.getApiKey();
        if (newPlainKey == null && !create) {
            // 未提交 Key：沿用已存密文
            row.setBaseUrl(request.getBaseUrl().trim());
            row.setModel(request.getModel().trim());
            row.setEnabled(true);
            mapper.updateById(row);
        } else {
            if (newPlainKey != null && !StringUtils.hasText(newPlainKey)) {
                throw new BusinessException(ErrorCode.PARAM_INVALID,
                        "API Key 不能为空字符串；如需清除请使用「恢复演示模式」");
            }
            if (newPlainKey == null) {
                // 首次配置必须给 Key：之前这里对 null 调 trim() 直接 NPE，前端只能看到 500
                throw new BusinessException(ErrorCode.PARAM_INVALID, "首次接入需要填写 API Key");
            }
            row.setBaseUrl(request.getBaseUrl().trim());
            row.setModel(request.getModel().trim());
            row.setApiKeyCipher(cipher.encrypt(newPlainKey.trim()));
            row.setEnabled(true);
            if (create) {
                mapper.insert(row);
            } else {
                mapper.updateById(row);
            }
        }
        log.info("模型接入配置已更新（来源 DB），model={}", request.getModel().trim());
        return status();
    }

    /**
     * 用待保存的同款参数做一次最小真实调用验证连通性，
     * apiKey 缺省时回退到已存密钥——先验证后保存由前端自行编排。
     */
    public String testConnection(LlmSettingsSaveRequest request) {
        validate(request);
        String key = StringUtils.hasText(request.getApiKey()) ? request.getApiKey().trim()
                : storedPlainKey();
        if (!StringUtils.hasText(key)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请填写 API Key 后再测试连接");
        }
        var props = new LlmProperties();
        props.setBaseUrl(request.getBaseUrl().trim());
        props.setModel(request.getModel().trim());
        props.setApiKey(key);
        props.setMock(false);
        props.setConnectTimeoutSeconds(llmProperties.getConnectTimeoutSeconds());
        props.setReadTimeoutSeconds(Math.min(llmProperties.getReadTimeoutSeconds(), 60));
        String reply;
        try {
            reply = new com.mojian.generation.llm.OpenAiCompatLlmClient(props)
                    .chat(new com.mojian.generation.llm.LlmRequest("你是一个连通性探针。",
                            "请只回复两个字：成功", "CONNECTIVITY_TEST"));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED,
                    "连接失败：" + e.getMessage());
        }
        return StringUtils.hasText(reply)
                ? reply.strip().substring(0, Math.min(reply.strip().length(), 80))
                : "服务已连通，但模型返回了空回复，请确认模型名称是否正确";
    }

    /** 恢复演示模式：删除界面配置，生效权交还环境变量/模拟模式 */
    public LlmSettingsStatusVO reset() {
        mapper.deleteById(SINGLETON_ID);
        log.info("模型接入配置已清除，恢复默认（环境变量/演示模式）");
        return status();
    }

    // ==================== 厂家预设与模型列表 ====================

    /** 内置的服务商预设 */
    public List<LlmProviderPreset.Preset> providers() {
        return LlmProviderPreset.PRESETS;
    }

    /**
     * 查某个接口地址下有哪些可用模型。
     *
     * <p><b>三层来源，从可靠到将就</b>：
     * <ol>
     *   <li><b>模型目录</b>（models.dev）：一个请求覆盖 232 家服务商，
     *       还带上下文长度与定价。这是首选 —— 实测 OpenCode Go 的
     *       {@code GET /models} 是 404，只有目录这条路走得通</li>
     *   <li><b>直接问服务商</b>：目录里没有的地址（自建网关、公司内部服务）试这个</li>
     *   <li>都拿不到就返回空，界面退回预设里的常用模型或手动填</li>
     * </ol>
     *
     * <p>注意 Key 在这一步<b>可能根本用不上</b>（目录是公开的）——
     * 所以不填 Key 也能看到模型列表，这对"先看看有哪些模型再决定充不充值"
     * 是很自然的用法。
     */
    public List<LlmModelVO> fetchModels(String baseUrl, String apiKey) {
        if (!StringUtils.hasText(baseUrl)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请先填写接口地址");
        }

        List<ModelsDevCatalog.ModelInfo> raw;
        // ① 公开目录
        List<ModelsDevCatalog.ModelInfo> fromCatalog = catalog.modelsFor(baseUrl);
        if (!fromCatalog.isEmpty()) {
            log.info("从模型目录匹配到 {} 个模型：{}", fromCatalog.size(), baseUrl);
            raw = fromCatalog;
        } else {
            // ② 直接问服务商（需要 Key）；拿不到就返回空，界面退回预设或手动填
            String key = StringUtils.hasText(apiKey) ? apiKey.trim() : storedPlainKey();
            raw = StringUtils.hasText(key) ? askProvider(baseUrl, key) : List.of();
        }

        java.util.Set<String> enabled = enabledModelIds();
        return raw.stream()
                // 没做过选择 = 全部启用；做过选择就按选择来
                .map(info -> LlmModelVO.of(info, enabled == null || enabled.contains(info.id())))
                .toList();
    }

    /** 问服务商要模型列表（{@code GET /models}）。不是每家都实现了 */
    private List<ModelsDevCatalog.ModelInfo> askProvider(String baseUrl, String key) {
        String url = baseUrl.trim().replaceAll("/+$", "") + "/models";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + key)
                    // 自报家门：有些网关会按 UA 拒绝通用 SDK 的名字
                    .header("User-Agent", "mojian-agent/0.1")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "接口地址格式不对：" + baseUrl);
        }

        HttpResponse<String> response;
        try {
            response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(llmProperties.getConnectTimeoutSeconds()))
                    .build()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, "拉取模型列表被中断");
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED,
                    "连不上 " + url + "：" + e.getMessage());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            // 404 是常见且正常的情况（服务商没实现这个接口），不该当成错误弹给用户。
            // 让调用方走"退回预设/手动填"那条路
            log.info("{} 没有提供模型列表（HTTP {}），退回预设", url, response.statusCode());
            return List.of();
        }
        return parseModelIds(response.body());
    }

    /**
     * 解析服务商 {@code GET /models} 的返回。
     *
     * <p>标准结构是 {@code {"data":[{"id":"..."}]}}，但各家略有出入。
     * 找不到就返回空 —— 让前端退回预设，而不是因为解析不出就整个流程卡死。
     */
    private List<ModelsDevCatalog.ModelInfo> parseModelIds(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode data = root.has("data") ? root.get("data") : root;
            List<ModelsDevCatalog.ModelInfo> models = new ArrayList<>();
            if (data.isArray()) {
                for (JsonNode node : data) {
                    JsonNode id = node.has("id") ? node.get("id") : node.get("name");
                    if (id != null && id.isTextual() && StringUtils.hasText(id.asText())) {
                        boolean vision = false;
                        JsonNode inputModes = node.path("modalities").path("input");
                        if (inputModes.isArray()) {
                            for (JsonNode mode : inputModes) {
                                if ("image".equalsIgnoreCase(mode.asText())) vision = true;
                            }
                        }
                        if (node.path("attachment").asBoolean(false)) vision = true;
                        // 服务商自己返回的列表没有思考强度信息，留空即可
                        models.add(new ModelsDevCatalog.ModelInfo(
                                id.asText().trim(), id.asText().trim(), null, null, null,
                                List.of(), vision));
                    }
                }
            }
            return models.stream()
                    .collect(java.util.stream.Collectors.toMap(
                            ModelsDevCatalog.ModelInfo::id, m -> m, (a, b) -> a))
                    .values().stream()
                    .sorted(java.util.Comparator.comparing(ModelsDevCatalog.ModelInfo::id))
                    .toList();
        } catch (Exception e) {
            log.warn("解析模型列表失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String storedPlainKey() {
        LlmSettings row = mapper.selectSingleton();
        return row == null ? null : cipher.decryptSafe(row.getApiKeyCipher());
    }

    private void validate(LlmSettingsSaveRequest request) {
        if (!StringUtils.hasText(request.getBaseUrl()) || !StringUtils.hasText(request.getModel())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "接口地址与模型名称不能为空");
        }
        // 与 DTO 上的 @Pattern 同口径，兜住非 MVC 入口的调用（测试/内部脚本）
        if (!request.getBaseUrl().trim().matches("^https?://\\S{1,280}")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "接口地址需以 http(s):// 开头且不含空白");
        }
    }

    /** 掩码：保留前3后4，其余以 • 遮蔽；过短密钥整体遮蔽 */
    public static String mask(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        if (key.length() <= 8) {
            return "••••";
        }
        return key.substring(0, 3) + "••••" + key.substring(key.length() - 4);
    }

    /**
     * 生效中的模型配置。record 携带 Key 明文：仅限 generation 内部瞬时使用。
     *
     * @param source 生效来源：DB / ENV
     */
    public record Effective(String baseUrl, String apiKey, String model, String source) {
    }
}
