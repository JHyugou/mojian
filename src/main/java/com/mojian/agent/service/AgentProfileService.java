package com.mojian.agent.service;

import com.mojian.agent.AgentRole;
import com.mojian.agent.entity.AgentProfile;
import com.mojian.agent.mapper.AgentProfileMapper;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.crypto.LocalSecretCipher;
import com.mojian.common.exception.BusinessException;
import com.mojian.settings.service.LlmSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
                profile.getContextLimit() == null ? 131_072 : profile.getContextLimit(),
                profile.getReserveOutputTokens() == null ? 8_192 : profile.getReserveOutputTokens());
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
