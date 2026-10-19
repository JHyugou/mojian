package com.mojian.settings.controller;

import com.mojian.common.api.ApiResponse;
import com.mojian.settings.dto.LlmProviderSaveRequest;
import com.mojian.settings.dto.LlmSettingsSaveRequest;
import com.mojian.settings.llm.LlmProviderPreset;
import com.mojian.settings.service.LlmProviderService;
import com.mojian.settings.vo.LlmModelVO;
import com.mojian.settings.vo.LlmProviderVO;
import com.mojian.settings.service.LlmSettingsService;
import com.mojian.settings.vo.LlmSettingsStatusVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 模型接入设置接口（FR-019）。
 * 所有响应中的 api-key 均为掩码，完整密钥不出后端（SRS 7.1）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LlmSettingsController {

    private final LlmSettingsService settingsService;
    private final LlmProviderService providerService;

    @GetMapping("/settings/llm")
    public ApiResponse<LlmSettingsStatusVO> status() {
        return ApiResponse.ok(settingsService.status());
    }

    /** 保存并启用界面配置。apiKey=null 表示沿用已存密钥 */
    @PutMapping("/settings/llm")
    public ApiResponse<LlmSettingsStatusVO> save(@Valid @RequestBody LlmSettingsSaveRequest request) {
        return ApiResponse.ok(settingsService.save(request));
    }

    /** 当前已配置的服务商（含各自模型库；Key 只出掩码） */
    @GetMapping("/settings/llm/provider-configs")
    public ApiResponse<List<LlmProviderVO>> providerConfigs() {
        return ApiResponse.ok(providerService.list());
    }

    /** 新增或修改一个服务商配置 */
    @PostMapping("/settings/llm/provider-configs")
    public ApiResponse<LlmProviderVO> saveProvider(@RequestBody LlmProviderSaveRequest request) {
        return ApiResponse.ok(providerService.save(request));
    }

    /** 刷新某个服务商的模型列表 */
    @PostMapping("/settings/llm/provider-configs/{providerId}/refresh-models")
    public ApiResponse<LlmProviderVO> refreshProviderModels(@PathVariable Long providerId) {
        return ApiResponse.ok(providerService.refreshModels(providerId));
    }

    /** 删除服务商配置；仍被 Agent 使用时拒绝 */
    @DeleteMapping("/settings/llm/provider-configs/{providerId}")
    public ApiResponse<Void> deleteProvider(@PathVariable Long providerId) {
        providerService.delete(providerId);
        return ApiResponse.ok();
    }

    /** 用待保存参数发起一次最小真实调用验证连通性（不落库） */
    @PostMapping("/settings/llm/test")
    public ApiResponse<String> test(@Valid @RequestBody LlmSettingsSaveRequest request) {
        return ApiResponse.ok(settingsService.testConnection(request));
    }

    /** 清除界面配置，恢复"环境变量/演示模式"默认链路 */
    @DeleteMapping("/settings/llm")
    public ApiResponse<LlmSettingsStatusVO> reset() {
        return ApiResponse.ok(settingsService.reset());
    }

    // ==================== 厂家预设与模型列表 ====================

    /** 内置的服务商预设（地址、控制台链接、说明） */
    @GetMapping("/settings/llm/providers")
    public ApiResponse<List<LlmProviderPreset.Preset>> providers() {
        return ApiResponse.ok(settingsService.providers());
    }

    /**
     * 查某个接口地址下有哪些可用模型。
     *
     * <p>三层来源：公开模型目录（models.dev，覆盖 232 家，还带上下文长度与定价）
     * → 直接问服务商 → 都拿不到就返回空，界面退回预设或手动填。
     *
     * <p>不需要 Key 也能调用：目录是公开的，而"先看看有哪些模型再决定"是很自然的用法。
     */
    @PostMapping("/settings/llm/models")
    public ApiResponse<Map<String, Object>> models(@RequestBody LlmSettingsSaveRequest request) {
        List<LlmModelVO> models =
                settingsService.fetchModels(request.getBaseUrl(), request.getApiKey());
        return ApiResponse.ok(Map.of(
                "source", models.isEmpty() ? "none" : "catalog-or-provider",
                "models", models));
    }

    /**
     * 保存"我启用哪些模型"。
     *
     * <p>一个网关往往能提供几十上百个模型，但没人会在下拉里翻三十项挑一个 ——
     * 所以要把用不上的关掉。传空数组表示全部启用。
     */
    @PutMapping("/settings/llm/models/enabled")
    public ApiResponse<Map<String, Object>> saveEnabledModels(@RequestBody Map<String, List<String>> body) {
        List<String> models = body.getOrDefault("models", List.of());
        settingsService.saveEnabledModels(models);
        return ApiResponse.ok(Map.of("saved", models.size()));
    }
}
