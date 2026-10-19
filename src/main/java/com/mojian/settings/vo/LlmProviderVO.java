package com.mojian.settings.vo;

import java.util.List;

/** 已配置服务商：Key 只出掩码，模型列表按该服务商缓存。 */
public record LlmProviderVO(Long id, String name, String presetKey, String baseUrl,
                            boolean apiKeyConfigured, String apiKeyMask,
                            List<LlmModelVO> models) {
}
