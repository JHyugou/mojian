package com.mojian.agent.config;

import com.mojian.agent.AgentLoopService;
import com.mojian.agent.AgentProperties;
import com.mojian.agent.llm.AgentLlmClient;
import com.mojian.agent.llm.OpenAiCompatAgentLlmClient;
import com.mojian.agent.service.AgentProfileService;
import com.mojian.agent.tool.ToolRegistry;
import com.mojian.generation.llm.LlmProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 装配层。
 *
 * <p>为什么把 AgentLoopService 放在这里用 {@code @Bean} 声明，而不是在它自己头上加 {@code @Service}：
 * 核心逻辑保持"不认识 Spring"，测试里就能直接 new 出来跑（现在就是这么测的）。
 * 依赖注入这类装配工作集中在装配层做，核心代码就永远是干净的。
 *
 * <p>工具不需要在这里登记：{@link ToolRegistry} 通过构造函数接收容器里所有
 * {@link com.mojian.agent.tool.AgentTool} 实现，新增工具只要加个 {@code @Component}。
 */
@Configuration
public class AgentConfig {

    /** 客户端按"角色"取配置，所以它依赖的是 AgentProfileService 而不是全局设置 */
    @Bean
    public AgentLlmClient agentLlmClient(LlmProperties llmProperties, AgentProfileService profileService) {
        return new OpenAiCompatAgentLlmClient(llmProperties, profileService);
    }

    @Bean
    public AgentLoopService agentLoopService(AgentLlmClient agentLlmClient,
                                             ToolRegistry toolRegistry,
                                             AgentProperties agentProperties) {
        return new AgentLoopService(agentLlmClient, toolRegistry, agentProperties);
    }
}
