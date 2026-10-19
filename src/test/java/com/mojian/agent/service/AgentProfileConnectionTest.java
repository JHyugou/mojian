package com.mojian.agent.service;

import com.mojian.agent.AgentRole;
import com.mojian.agent.dto.AgentProfileUpdateRequest;
import com.mojian.agent.dto.AgentProfileVO;
import com.mojian.settings.dto.LlmProviderSaveRequest;
import com.mojian.settings.service.LlmProviderService;
import com.mojian.settings.vo.LlmProviderVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 双 Agent 可以各自使用不同服务商：角色级 baseUrl/apiKey 覆盖必须能真正生效。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentProfileConnectionTest {

    @Autowired
    private AgentProfileService profileService;
    @Autowired
    private LlmProviderService providerService;

    @Test
    @DisplayName("策划与写作可分别覆盖 baseUrl 和 Key，解析时使用本角色的连接")
    void 角色独立连接() {
        String writerModel = "writer-provider-model";
        AgentProfileUpdateRequest writer = new AgentProfileUpdateRequest(
                writerModel, 0L, null, null, null, null,
                "https://writer.example.com/v1", "writer-secret-key");
        profileService.update(AgentRole.WRITER, writer);

        AgentProfileService.ResolvedModel resolved = profileService.resolve(AgentRole.WRITER);
        assertThat(resolved.baseUrl()).isEqualTo("https://writer.example.com/v1");
        assertThat(resolved.apiKey()).isEqualTo("writer-secret-key");
        assertThat(resolved.model()).isEqualTo(writerModel);

        AgentProfileVO vo = profileService.toVo(profileService.require(AgentRole.WRITER));
        assertThat(vo.baseUrlSource()).isEqualTo("PROFILE");
        assertThat(vo.apiKeySource()).isEqualTo("PROFILE");
        assertThat(vo.apiKeyConfigured()).isTrue();
    }

    @Test
    @DisplayName("角色选择多服务商后，解析使用该服务商的地址与 Key")
    void 角色使用选中的服务商() {
        LlmProviderSaveRequest providerRequest = new LlmProviderSaveRequest();
        providerRequest.setName("测试服务商");
        providerRequest.setBaseUrl("https://provider.example.com/v1");
        providerRequest.setApiKey("provider-secret-key");
        LlmProviderVO provider = providerService.save(providerRequest);

        AgentProfileUpdateRequest writer = new AgentProfileUpdateRequest(
                "provider-model", provider.id(), null, null, null, null, null, null);
        profileService.update(AgentRole.WRITER, writer);

        AgentProfileService.ResolvedModel resolved = profileService.resolve(AgentRole.WRITER);
        assertThat(resolved.baseUrl()).isEqualTo("https://provider.example.com/v1");
        assertThat(resolved.apiKey()).isEqualTo("provider-secret-key");
        assertThat(resolved.model()).isEqualTo("provider-model");

        AgentProfileVO vo = profileService.toVo(profileService.require(AgentRole.WRITER));
        assertThat(vo.providerId()).isEqualTo(provider.id());
        assertThat(vo.providerName()).isEqualTo("测试服务商");
        assertThat(vo.baseUrlSource()).isEqualTo("PROVIDER");
        assertThat(vo.apiKeySource()).isEqualTo("PROVIDER");
    }
}
