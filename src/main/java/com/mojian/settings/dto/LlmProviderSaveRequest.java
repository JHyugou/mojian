package com.mojian.settings.dto;

import lombok.Data;

/** 新增/修改一个模型服务商。apiKey=null 表示沿用已存；空字符串表示清除。 */
@Data
public class LlmProviderSaveRequest {

    private Long id;

    private String name;

    private String presetKey;

    private String baseUrl;

    private String apiKey;
}
