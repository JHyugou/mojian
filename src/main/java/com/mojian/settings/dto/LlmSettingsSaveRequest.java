package com.mojian.settings.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模型接入设置保存 / 测试连接共用请求体。
 *
 * apiKey 语义（后端按值区分）：
 * - null：保留数据库中已存的密钥不变；
 * - ""：清除已存密钥；
 * - 非空字符串：替换为新密钥。
 */
@Data
public class LlmSettingsSaveRequest {

    /** OpenAI 兼容接口地址 */
    @NotBlank(message = "接口地址不能为空")
    @Pattern(regexp = "^https?://\\S{1,280}", message = "接口地址需以 http(s):// 开头且不含空白")
    private String baseUrl;

    @NotBlank(message = "模型名称不能为空")
    @Size(max = 100, message = "模型名称不能超过100字")
    private String model;

    @Size(max = 300, message = "API Key 过长")
    private String apiKey;
}
