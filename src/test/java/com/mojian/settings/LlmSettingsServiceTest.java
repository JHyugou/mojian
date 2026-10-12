package com.mojian.settings;

import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.llm.LlmClient;
import com.mojian.generation.llm.RoutingLlmClient;
import com.mojian.settings.dto.LlmSettingsSaveRequest;
import com.mojian.settings.service.LlmSettingsService;
import com.mojian.settings.vo.LlmSettingsStatusVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 模型接入设置集成测试（FR-019）：保存即热切换、Key 掩码、优先级与恢复演示模式。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LlmSettingsServiceTest {

    private static final String RAW_KEY = "sk-live-key-9f8e7d6c5b4a";

    @Autowired
    private LlmSettingsService settingsService;
    @Autowired
    private LlmClient llmClient;

    private LlmSettingsSaveRequest request(String baseUrl, String model, String apiKey) {
        LlmSettingsSaveRequest req = new LlmSettingsSaveRequest();
        req.setBaseUrl(baseUrl);
        req.setModel(model);
        req.setApiKey(apiKey);
        return req;
    }

    @Test
    void saveEnablesLiveModeAndSwitchesRoutingImmediately() {
        assertEquals("DEMO", settingsService.status().getMode(), "初始应为演示模式");

        LlmSettingsStatusVO after = settingsService.save(
                request("https://api.example.com", "test-model", RAW_KEY));

        assertEquals("LIVE", after.getMode());
        assertEquals("DB", after.getSource());
        assertEquals("https://api.example.com", after.getBaseUrl());
        assertEquals("test-model", after.getModel());

        // Key 只能以掩码出现，完整密钥不出后端
        assertTrue(after.getApiKeyMasked().startsWith("sk-"), "掩码保留前缀");
        assertTrue(after.getApiKeyMasked().endsWith("b4a"), "掩码保留末4位");
        assertFalse(after.toString().contains(RAW_KEY), "响应不得包含完整密钥");
    }

    @Test
    void statusNeverExposesFullApiKey() {
        settingsService.save(request("https://api.example.com", "m1", RAW_KEY));
        LlmSettingsStatusVO vo = settingsService.status();
        assertFalse(vo.toString().contains(RAW_KEY), "状态视图任何字段不得包含完整密钥");
        assertFalse(vo.toString().contains("9f8e7d6c5b4a".substring(0, 8)), "密钥主体不得回显");
        assertNotNull(vo.getApiKeyMasked());
    }

    @Test
    void saveWithoutApiKeyKeepsStoredCipher() {
        settingsService.save(request("https://api.example.com/v1", "model-a", RAW_KEY));
        LlmSettingsStatusVO kept = settingsService.save(
                request("https://api.example.com/v2", "model-b", null));
        assertEquals("LIVE", kept.getMode(), "未提交 Key 时沿用已存密钥，仍为接入态");
        assertEquals("https://api.example.com/v2", kept.getBaseUrl());
        assertEquals(LlmSettingsService.mask(RAW_KEY), kept.getApiKeyMasked(), "掩码不变说明密文未被替换");
    }

    @Test
    void blankApiKeyRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> settingsService.save(request("https://api.example.com", "m", "")));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getErrorCode().getCode());
    }

    @Test
    void invalidUrlRejected() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> settingsService.save(request("ftp://nope", "m", RAW_KEY)));
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), e.getErrorCode().getCode());

        assertThrows(BusinessException.class,
                () -> settingsService.save(request("https://has space.io", "m", RAW_KEY)));
    }

    @Test
    void resetReturnsToDemoModeAndRoutingFallsBackToMock() {
        RoutingLlmClient routing = (RoutingLlmClient) llmClient;

        settingsService.save(request("https://api.example.com", "m", RAW_KEY));
        assertTrue(routing.activeDescription().contains("api.example.com"),
                "保存后路由应立即切到界面配置的接口");

        LlmSettingsStatusVO after = settingsService.reset();
        assertEquals("DEMO", after.getMode());
        assertEquals("Mock（演示模式）", routing.activeDescription(), "清除后应回落演示模式");
    }
}
