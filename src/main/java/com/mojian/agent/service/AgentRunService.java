package com.mojian.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojian.agent.AgentContext;
import com.mojian.agent.AgentResult;
import com.mojian.agent.AgentStep;
import com.mojian.agent.entity.AgentRun;
import com.mojian.agent.mapper.AgentRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行轨迹记录。
 *
 * <p>记录动作绝不能影响主流程：任何失败只记 WARN。
 * 但数据本身很重要 —— 没有它，"Agent 为什么忘了查资料/为什么花了这么多钱"
 * 就只能靠猜；有了它，这些都是可以查、可以统计、可以做评测的事实。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentRunService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_TRACE_ITEMS = 40;
    private static final int MAX_RESULT_EXCERPT = 800;

    private final AgentRunMapper runMapper;
    private final AgentProfileService profileService;

    /** 成功/取消的运行：有完整结果 */
    public void record(AgentContext context, AgentResult result, long latencyMs, String contextSummary) {
        if (result == null) {
            return;
        }
        record(context, result.status().name(), result.steps(), result.trace(),
                result.usage().promptTokens(), result.usage().cachedTokens(),
                result.usage().completionTokens(), latencyMs, contextSummary, null);
    }

    /** 失败的运行：没有结果，但"失败发生在哪一步、花了多少"同样值得记 */
    public void recordFailure(AgentContext context, long latencyMs, String error) {
        record(context, "FAILED", 0, List.of(), 0, 0, 0, latencyMs, null, error);
    }

    private void record(AgentContext context, String status, int steps, List<AgentStep> trace,
                        int promptTokens, int cachedTokens, int completionTokens,
                        long latencyMs, String contextSummary, String error) {
        try {
            AgentRun run = new AgentRun();
            run.setProjectId(context.projectId());
            run.setConversationId(context.conversationId());
            run.setAgentRole(context.agentRole());
            run.setModel(resolveModel(context.agentRole()));
            run.setStatus(status);
            run.setSteps(steps);
            run.setPromptTokens(promptTokens);
            run.setCachedTokens(cachedTokens);
            run.setCompletionTokens(completionTokens);
            run.setLatencyMs(latencyMs);
            run.setContextSummary(abbreviate(contextSummary, 2000));
            run.setErrorMessage(abbreviate(error, 1000));
            run.setTraceJson(writeTrace(trace));
            runMapper.insert(run);
        } catch (Exception e) {
            log.warn("记录 Agent 运行轨迹失败（不影响本次运行）: {}", e.getMessage());
        }
    }

    private String resolveModel(String role) {
        try {
            return profileService.resolve(role).model();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String writeTrace(List<AgentStep> trace) {
        if (trace == null || trace.isEmpty()) {
            return null;
        }
        try {
            List<Map<String, Object>> items = new ArrayList<>();
            for (AgentStep step : trace.stream().limit(MAX_TRACE_ITEMS).toList()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("step", step.step());
                item.put("tool", step.toolName());
                item.put("arguments", abbreviate(step.arguments(), 600));
                item.put("result", abbreviate(step.result(), MAX_RESULT_EXCERPT));
                items.add(item);
            }
            return MAPPER.writeValueAsString(items);
        } catch (Exception e) {
            return null;
        }
    }

    private String abbreviate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    /** 最近运行（调试与评测用） */
    public List<AgentRun> recent(int limit) {
        return runMapper.selectRecent(limit);
    }

    public List<AgentRun> recentByProject(Long projectId, int limit) {
        return runMapper.selectByProject(projectId, limit);
    }
}
