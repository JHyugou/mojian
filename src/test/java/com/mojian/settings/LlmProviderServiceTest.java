package com.mojian.settings;

import com.mojian.settings.dto.LlmProviderSaveRequest;
import com.mojian.settings.service.LlmProviderService;
import com.mojian.settings.vo.LlmModelVO;
import com.mojian.settings.vo.LlmProviderVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 自定义服务商：手动模型目录随保存写入，重复 ID 去重。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LlmProviderServiceTest {

    @Autowired
    private LlmProviderService providerService;

    @Test
    @DisplayName("保存自定义服务商时写入手动模型目录")
    void 保存手动模型目录() {
        LlmProviderSaveRequest request = new LlmProviderSaveRequest();
        request.setName("测试中转站");
        request.setPresetKey("custom");
        request.setBaseUrl("https://gateway.example.com/v1");
        request.setApiKey("sk-test");
        request.setModels(List.of(
                new LlmModelVO("gpt-4o", "gpt-4o", null, null, null, List.of(), false, true),
                new LlmModelVO("gpt-4o", "重复 ID", null, null, null, List.of(), false, true),
                new LlmModelVO("my-model", "我的模型", 128000, null, null, List.of(), false, true)
        ));

        LlmProviderVO saved = providerService.save(request);

        assertThat(saved.models()).extracting(LlmModelVO::id)
                .containsExactly("gpt-4o", "my-model");
        assertThat(saved.models().get(1).name()).isEqualTo("我的模型");
        providerService.delete(saved.id());
    }
}
