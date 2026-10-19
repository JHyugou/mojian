package com.mojian.agent.service;

import com.mojian.agent.AgentRole;
import com.mojian.agent.dto.AgentProfileUpdateRequest;
import com.mojian.agent.dto.AgentProfileVO;
import com.mojian.agent.service.AgentProfileService;
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

    @Test
    @DisplayName("策划与写作可分别覆盖 baseUrl 和 Key，解析时使用本角色的连接")
    void 角色独立连接() {
        String writerModel = "writer-provider-model";
        AgentProfileUpdateRequest writer = new AgentProfileUpdateRequest(
                writerModel, null, null, null, null,
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
}
