package com.mojian.settings.controller;

import com.mojian.common.api.ApiResponse;
import com.mojian.settings.dto.LlmSettingsSaveRequest;
import com.mojian.settings.llm.LlmProviderPreset;
import com.mojian.settings.service.LlmSettingsService;
import com.mojian.settings.vo.LlmSettingsStatusVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 模型接入设置接口（FR-019）。
 * 所有响应中的 api-key 均为掩码，完整密钥不出后端（SRS 7.1）。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class LlmSettingsController {

    private final LlmSettingsService settingsService;

    @GetMapping("/settings/llm")
    public ApiResponse<LlmSettingsStatusVO> status() {
        return ApiResponse.ok(settingsService.status());
    }

    /** 保存并启用界面配置。apiKey=null 表示沿用已存密钥 */
    @PutMapping("/settings/llm")
    public ApiResponse<LlmSettingsStatusVO> save(@Valid @RequestBody LlmSettingsSaveRequest request) {
        return ApiResponse.ok(settingsService.save(request));
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
     * 拉取某个服务商的可用模型列表。
     *
     * <p>比"预设里写死模型名"可靠：模型上下线很快，写死的名字过一阵就成了
     * "看着对但调不通"的错误答案，而作者会以为是自己 Key 的问题。
     */
    @PostMapping("/settings/llm/models")
    public ApiResponse<List<String>> models(@RequestBody LlmSettingsSaveRequest request) {
        return ApiResponse.ok(settingsService.fetchModels(request.getBaseUrl(), request.getApiKey()));
    }
}
