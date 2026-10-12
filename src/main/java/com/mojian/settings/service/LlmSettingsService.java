package com.mojian.settings.service;

import com.mojian.common.api.ErrorCode;
import com.mojian.common.crypto.LocalSecretCipher;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.llm.LlmProperties;
import com.mojian.settings.dto.LlmSettingsSaveRequest;
import com.mojian.settings.entity.LlmSettings;
import com.mojian.settings.mapper.LlmSettingsMapper;
import com.mojian.settings.vo.LlmSettingsStatusVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

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

    private static final String ENSURE_TABLE_DDL = """
            CREATE TABLE IF NOT EXISTS llm_settings (
                id              BIGINT       NOT NULL COMMENT '主键（固定为1）',
                base_url        VARCHAR(300) DEFAULT NULL COMMENT 'OpenAI兼容接口地址',
                api_key_cipher  VARCHAR(600) DEFAULT NULL COMMENT 'API密钥密文（本机密钥文件AES-GCM加密）',
                model           VARCHAR(100) DEFAULT NULL COMMENT '模型名称',
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

    /** 老库升级友好：启动时幂等建表，无需手动重跑 schema.sql */
    @PostConstruct
    public void ensureTable() {
        jdbcTemplate.execute(ENSURE_TABLE_DDL);
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
