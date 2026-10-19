package com.mojian.settings.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojian.agent.entity.AgentProfile;
import com.mojian.agent.mapper.AgentProfileMapper;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.crypto.LocalSecretCipher;
import com.mojian.common.exception.BusinessException;
import com.mojian.settings.dto.LlmProviderSaveRequest;
import com.mojian.settings.entity.LlmProvider;
import com.mojian.settings.entity.LlmSettings;
import com.mojian.settings.llm.LlmProviderPreset;
import com.mojian.settings.mapper.LlmProviderMapper;
import com.mojian.settings.mapper.LlmSettingsMapper;
import com.mojian.settings.vo.LlmModelVO;
import com.mojian.settings.vo.LlmProviderVO;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 多服务商配置：每个服务商独立保存 Base URL / API Key，模型库按服务商聚合。
 *
 * <p>旧版本只有一个 llm_settings 单例行；启动时会把旧配置迁移成第一个 provider，
 * 这样已有用户的 Key 和模型选择不会丢。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<LlmModelVO>> MODEL_LIST =
            new TypeReference<>() { };

    private static final String PROVIDER_DDL = """
            CREATE TABLE IF NOT EXISTS llm_provider (
                id             BIGINT       NOT NULL,
                name           VARCHAR(100) NOT NULL,
                preset_key     VARCHAR(50)  DEFAULT NULL,
                base_url       VARCHAR(300) NOT NULL,
                api_key_cipher VARCHAR(600) DEFAULT NULL,
                models_json    MEDIUMTEXT   DEFAULT NULL,
                enabled        TINYINT      NOT NULL DEFAULT 1,
                created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                PRIMARY KEY (id),
                KEY idx_llm_provider_preset (preset_key)
            ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '模型服务商配置'
            """;

    private final LlmProviderMapper providerMapper;
    private final LlmSettingsMapper llmSettingsMapper;
    private final AgentProfileMapper profileMapper;
    private final LlmSettingsService llmSettingsService;
    private final LocalSecretCipher cipher;
    private final JdbcTemplate jdbcTemplate;

    private volatile boolean migrated;

    @PostConstruct
    public void init() {
        jdbcTemplate.execute(PROVIDER_DDL);
        if (!columnExists("agent_profile", "provider_id")) {
            jdbcTemplate.execute("ALTER TABLE agent_profile ADD COLUMN provider_id BIGINT DEFAULT NULL "
                    + "COMMENT '选中的服务商 id（llm_provider）' AFTER model");
            log.info("agent_profile.provider_id 已自动补列");
        }
        try {
            migrateLegacyIfNeeded();
        } catch (Exception e) {
            // AgentSchemaInitializer 可能还没建好 agent_profile；页面首次打开时再试
            log.warn("旧模型配置迁移延后：{}", e.getMessage());
        }
    }

    public synchronized void migrateLegacyIfNeeded() {
        if (migrated) return;
        Long count = providerMapper.selectCount(null);
        if (count != null && count > 0) {
            migrated = true;
            return;
        }
        LlmSettings legacy = llmSettingsMapper.selectSingleton();
        Long legacyProviderId = null;
        if (legacy != null && StringUtils.hasText(legacy.getBaseUrl())
                && StringUtils.hasText(legacy.getApiKeyCipher())) {
            LlmProvider provider = new LlmProvider();
            provider.setName(presetName(legacy.getBaseUrl(), "默认服务商"));
            provider.setPresetKey(presetKey(legacy.getBaseUrl()));
            provider.setBaseUrl(legacy.getBaseUrl().trim());
            provider.setApiKeyCipher(legacy.getApiKeyCipher());
            provider.setEnabled(true);
            providerMapper.insert(provider);
            legacyProviderId = provider.getId();
            log.info("旧配置已迁移为服务商：{}", provider.getName());
        }

        for (AgentProfile profile : profileMapper.selectList(null)) {
            if (profile.getProviderId() != null) continue;
            if (StringUtils.hasText(profile.getBaseUrl()) && StringUtils.hasText(profile.getApiKeyCipher())) {
                LlmProvider own = new LlmProvider();
                own.setName(roleName(profile.getRole()) + " 独立服务商");
                own.setPresetKey(presetKey(profile.getBaseUrl()));
                own.setBaseUrl(profile.getBaseUrl().trim());
                own.setApiKeyCipher(profile.getApiKeyCipher());
                own.setEnabled(true);
                providerMapper.insert(own);
                profile.setProviderId(own.getId());
                profileMapper.updateById(profile);
            } else if (legacyProviderId != null) {
                profile.setProviderId(legacyProviderId);
                profileMapper.updateById(profile);
            }
        }
        migrated = true;
    }

    public List<LlmProviderVO> list() {
        migrateLegacyIfNeeded();
        return providerMapper.selectList(Wrappers.<LlmProvider>lambdaQuery()
                        .orderByAsc(LlmProvider::getCreatedAt))
                .stream().map(this::toVo).toList();
    }

    public LlmProvider require(Long id) {
        LlmProvider provider = providerMapper.selectById(id);
        if (provider == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "服务商不存在：" + id);
        }
        return provider;
    }

    @Transactional
    public LlmProviderVO save(LlmProviderSaveRequest request) {
        migrateLegacyIfNeeded();
        if (!StringUtils.hasText(request.getBaseUrl())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "接口地址不能为空");
        }
        String baseUrl = request.getBaseUrl().trim();
        if (!baseUrl.matches("^https?://\\S{1,280}")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "接口地址需以 http(s):// 开头且不含空白");
        }

        LlmProvider provider = null;
        if (request.getId() != null) {
            provider = require(request.getId());
        } else if (StringUtils.hasText(request.getPresetKey())
                && !"custom".equals(request.getPresetKey().trim())) {
            // 同一个内置预设只保留一份配置；自定义服务商允许开多个
            provider = providerMapper.selectOne(Wrappers.<LlmProvider>lambdaQuery()
                    .eq(LlmProvider::getPresetKey, request.getPresetKey().trim())
                    .last("limit 1"));
        }
        boolean create = provider == null;
        if (create) {
            provider = new LlmProvider();
            provider.setEnabled(true);
        }
        provider.setName(StringUtils.hasText(request.getName())
                ? request.getName().trim() : presetName(baseUrl, "自定义服务商"));
        provider.setPresetKey(StringUtils.hasText(request.getPresetKey())
                ? request.getPresetKey().trim() : presetKey(baseUrl));
        provider.setBaseUrl(baseUrl);
        if (request.getApiKey() != null) {
            provider.setApiKeyCipher(StringUtils.hasText(request.getApiKey())
                    ? cipher.encrypt(request.getApiKey().trim()) : null);
        }
        if (create && !StringUtils.hasText(provider.getApiKeyCipher())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "新服务商必须填写 API Key");
        }
        if (create) {
            providerMapper.insert(provider);
        } else {
            providerMapper.updateById(provider);
        }
        log.info("服务商配置已保存：{}（{}）", provider.getName(), provider.getBaseUrl());
        return toVo(provider);
    }

    @Transactional
    public LlmProviderVO refreshModels(Long id) {
        LlmProvider provider = require(id);
        String key = cipher.decryptSafe(provider.getApiKeyCipher());
        if (!StringUtils.hasText(key)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该服务商还没有配置 API Key");
        }
        List<LlmModelVO> models = llmSettingsService.fetchProviderModels(provider.getBaseUrl(), key);
        try {
            provider.setModelsJson(MAPPER.writeValueAsString(models));
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "模型列表序列化失败：" + e.getMessage());
        }
        providerMapper.updateById(provider);
        log.info("服务商 {} 刷新到 {} 个模型", provider.getName(), models.size());
        return toVo(provider);
    }

    @Transactional
    public void delete(Long id) {
        LlmProvider provider = require(id);
        Long used = profileMapper.selectCount(Wrappers.<AgentProfile>lambdaQuery()
                .eq(AgentProfile::getProviderId, id));
        if (used != null && used > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "还有 Agent 正在使用这个服务商，请先把角色模型切到其他服务商");
        }
        providerMapper.deleteById(id);
        log.info("已删除服务商配置：{}", provider.getName());
    }

    public LlmProviderVO toVo(LlmProvider provider) {
        String key = cipher.decryptSafe(provider.getApiKeyCipher());
        return new LlmProviderVO(provider.getId(), provider.getName(), provider.getPresetKey(),
                provider.getBaseUrl(), StringUtils.hasText(key), mask(key), parseModels(provider.getModelsJson()));
    }

    private List<LlmModelVO> parseModels(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            return MAPPER.readValue(json, MODEL_LIST);
        } catch (Exception e) {
            log.warn("解析服务商模型缓存失败：{}", e.getMessage());
            return List.of();
        }
    }

    private String mask(String key) {
        if (!StringUtils.hasText(key)) return null;
        return key.length() <= 8 ? "****" : key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private String presetName(String baseUrl, String fallback) {
        for (LlmProviderPreset.Preset preset : LlmProviderPreset.PRESETS) {
            if (StringUtils.hasText(preset.baseUrl())
                    && preset.baseUrl().replaceAll("/+$", "").equals(baseUrl.replaceAll("/+$", ""))) {
                return preset.name();
            }
        }
        return fallback;
    }

    private String presetKey(String baseUrl) {
        for (LlmProviderPreset.Preset preset : LlmProviderPreset.PRESETS) {
            if (StringUtils.hasText(preset.baseUrl())
                    && preset.baseUrl().replaceAll("/+$", "").equals(baseUrl.replaceAll("/+$", ""))) {
                return preset.id();
            }
        }
        return null;
    }

    private String roleName(String role) {
        return "WRITER".equals(role) ? "写作" : "策划";
    }

    private boolean columnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return count != null && count > 0;
    }
}
