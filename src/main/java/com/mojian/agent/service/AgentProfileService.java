package com.mojian.agent.service;

import com.mojian.agent.AgentRole;
import com.mojian.agent.context.AgentContextBudget;
import com.mojian.agent.dto.AgentProfileUpdateRequest;
import com.mojian.agent.dto.AgentProfileVO;
import com.mojian.agent.entity.AgentProfile;
import com.mojian.agent.llm.ModelContextWindow;
import com.mojian.agent.mapper.AgentProfileMapper;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.crypto.LocalSecretCipher;
import com.mojian.common.exception.BusinessException;
import com.mojian.settings.service.LlmSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 角色配置的读取与解析。
 *
 * <p>解决的是一件事：<b>"这次调用该用哪个模型、哪个密钥、多长的上下文预算"</b>。
 *
 * <p>优先级设计：接口地址与密钥<b>默认复用 llm_settings</b>（同一家服务商只需配一次密钥），
 * 但角色配置里可以单独覆盖 —— 将来若要"策划用 A 家、写作用 B 家"，不用改代码。
 * 模型名则<b>永远来自角色配置</b>，因为"主副 Agent 不同模型"正是这个表存在的理由。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentProfileService {

    private final AgentProfileMapper profileMapper;
    private final LlmSettingsService llmSettingsService;
    private final LocalSecretCipher cipher;

    public List<AgentProfile> list() {
        return profileMapper.selectList(null);
    }

    public AgentProfile require(String role) {
        AgentProfile profile = profileMapper.selectByRole(role);
        if (profile == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "Agent 角色配置不存在: " + role);
        }
        return profile;
    }

    /**
     * 更新角色配置（设置页用）。
     *
     * <p>字段语义见 {@link AgentProfileUpdateRequest}：null=不变，空串=清除覆盖。
     * 密钥用本机密钥加密后落库，明文不出这个方法的栈帧。
     */
    @Transactional
    public AgentProfile update(String role, AgentProfileUpdateRequest request) {
        AgentProfile profile = require(role);

        if (StringUtils.hasText(request.model())) {
            profile.setModel(request.model().trim());
        }
        if (request.temperature() != null) {
            profile.setTemperature(request.temperature());
        }
        if (request.maxSteps() != null) {
            profile.setMaxSteps(request.maxSteps());
        }
        if (request.contextLimit() != null) {
            profile.setContextLimit(request.contextLimit());
        }
        if (request.baseUrl() != null) {
            profile.setBaseUrl(StringUtils.hasText(request.baseUrl()) ? request.baseUrl().trim() : null);
        }
        if (request.apiKey() != null) {
            profile.setApiKeyCipher(StringUtils.hasText(request.apiKey())
                    ? cipher.encrypt(request.apiKey().trim()) : null);
        }

        profileMapper.updateById(profile);
        log.info("更新角色配置 {}：模型={}，接口地址={}，密钥={}", role, profile.getModel(),
                StringUtils.hasText(profile.getBaseUrl()) ? profile.getBaseUrl() : "（沿用全局设置）",
                StringUtils.hasText(profile.getApiKeyCipher()) ? "（本角色单独配置）" : "（沿用全局设置）");
        return profile;
    }

    /** 对外视图：密钥只出掩码，完整值不出后端 */
    public AgentProfileVO toVo(AgentProfile profile) {
        boolean ownKey = StringUtils.hasText(profile.getApiKeyCipher());
        boolean ownUrl = StringUtils.hasText(profile.getBaseUrl());
        LlmSettingsService.Effective global = llmSettingsService.effective();

        return new AgentProfileVO(
                profile.getRole(),
                profile.getModel(),
                profile.getTemperature(),
                profile.getMaxSteps() == null ? 10 : profile.getMaxSteps(),
                resolveContextLimit(profile),
                profile.getReserveOutputTokens() == null ? 8_192 : profile.getReserveOutputTokens(),
                ownUrl ? profile.getBaseUrl() : (global == null ? null : global.baseUrl()),
                ownUrl ? "PROFILE" : "GLOBAL",
                ownKey || (global != null && StringUtils.hasText(global.apiKey())),
                ownKey ? "（本角色单独配置）" : mask(global == null ? null : global.apiKey()));
    }

    private String mask(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        return key.length() <= 8
                ? "****"
                : key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /**
     * 学到模型真实的上下文上限后写回配置。
     *
     * <p>调用方是"撞了一次超限墙"的地方（见 {@code ContextLimitParser}）。
     * 写回后再组装上下文就按真实上限算预算，用户换模型也不用改代码。
     */
    public void learnContextLimit(String role, int limit) {
        AgentProfile profile = profileMapper.selectByRole(role);
        if (profile == null || Objects.equals(profile.getContextLimit(), limit)) {
            return;
        }
        profile.setContextLimit(limit);
        profileMapper.updateById(profile);
        log.info("学到 {} 的上下文上限: {} token（已写回 agent_profile）", role, limit);
    }

    /** 解析出该角色这次调用实际要用的接入信息 */
    public ResolvedModel resolve(String role) {
        AgentProfile profile = require(role);
        if (!Boolean.TRUE.equals(profile.getEnabled())) {
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, role + " 角色的模型配置已停用");
        }

        LlmSettingsService.Effective fallback = llmSettingsService.effective();

        String baseUrl = StringUtils.hasText(profile.getBaseUrl())
                ? profile.getBaseUrl()
                : (fallback == null ? null : fallback.baseUrl());

        String apiKey = StringUtils.hasText(profile.getApiKeyCipher())
                ? cipher.decryptSafe(profile.getApiKeyCipher())
                : (fallback == null ? null : fallback.apiKey());

        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(apiKey)) {
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED,
                    "还没有配置模型接入信息：请先在设置页填写接口地址与密钥");
        }
        if (!StringUtils.hasText(profile.getModel())) {
            throw new BusinessException(ErrorCode.LLM_REQUEST_FAILED, role + " 角色没有配置模型名");
        }

        return new ResolvedModel(
                role,
                baseUrl,
                apiKey,
                profile.getModel(),
                profile.getTemperature() == null ? 0.8d : profile.getTemperature().doubleValue(),
                profile.getMaxSteps() == null ? 10 : profile.getMaxSteps(),
                resolveContextLimit(profile),
                profile.getReserveOutputTokens() == null ? 8_192 : profile.getReserveOutputTokens());
    }

    /**
     * 上下文上限的取值顺序：<b>存储值（学到的/手工配的）→ 实测对照表 → 保守默认</b>。
     *
     * <p>值为 0 表示"未知"。这个语义是必需的：如果数据库默认就写一个保守值（比如 131072），
     * 系统会永远撞不到真实的墙，也就永远学不到真实上限 —— 保守值变成自我实现的预言。
     * 之前正是这样：真实上限是 1M，界面却已经显示"上下文占用 79%，该启用检索了"。
     */
    private int resolveContextLimit(AgentProfile profile) {
        Integer stored = profile.getContextLimit();
        if (stored != null && stored > 0) {
            return stored;
        }
        int fromTable = ModelContextWindow.of(profile.getModel());
        log.debug("{} 的上下文上限未知，按对照表取 {}（模型 {}，实测过={}）",
                profile.getRole(), fromTable, profile.getModel(),
                ModelContextWindow.measured(profile.getModel()));
        return fromTable;
    }

    /**
     * 该角色这次可用的上下文预算（不解析密钥，只读配置 —— 用于展示、预估与组装上下文）。
     */
    public AgentContextBudget budgetOf(String role) {
        AgentProfile profile = require(role);
        int reserve = profile.getReserveOutputTokens() == null ? 8_192 : profile.getReserveOutputTokens();
        return AgentContextBudget.of(resolveContextLimit(profile), reserve);
    }

    /**
     * 解析结果 —— 一次调用所需的全部接入参数。
     * 刻意做成不可变记录：它会被传给模型客户端，中途被改会很难排查。
     */
    public record ResolvedModel(String agentRole, String baseUrl, String apiKey, String model,
                                double temperature, int maxSteps,
                                int contextLimit, int reserveOutputTokens) {

        public boolean isPlanner() {
            return AgentRole.PLANNER.equals(agentRole);
        }
    }
}
