package com.mojian.agent;

import com.mojian.agent.llm.AgentLlmClient;
import com.mojian.agent.llm.AgentLlmResponse;
import com.mojian.agent.llm.AgentMessage;
import com.mojian.agent.llm.AgentToolCall;
import com.mojian.agent.tool.AgentTool;
import com.mojian.agent.tool.ToolRegistry;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent 循环的行为测试。
 *
 * <p>这份文件就是"什么叫做对了"的可执行版本：用**假模型**（不联网、不花钱、结果完全可预测）
 * 把循环的每条出口、每种意外都走一遍。
 *
 * <p>为什么要用假模型而不是真模型：
 * <ul>
 *   <li>真模型每次回答都不一样，没法断言"必须发生什么"</li>
 *   <li>假模型可以精确构造极端情况：模型一直要调工具、工具名写错了、工具炸了</li>
 * </ul>
 */
class AgentLoopServiceTest {

    private static final AgentContext CONTEXT = AgentContext.of(1001L);

    // ==================== 测试替身 ====================

    /** 假模型：按剧本回答，并记录每一轮收到了什么包裹 */
    private static class FakeLlm implements AgentLlmClient {

        private final Deque<AgentLlmResponse> script = new ArrayDeque<>();
        private AgentLlmResponse fallback;
        private List<AgentMessage> lastMessages = List.of();
        private List<AgentTool> lastTools = List.of();
        private int callCount = 0;

        FakeLlm then(AgentLlmResponse response) {
            script.add(response);
            return this;
        }

        /** 剧本用完之后一直返回它 —— 用来模拟"模型停不下来" */
        FakeLlm thenAlways(AgentLlmResponse response) {
            this.fallback = response;
            return this;
        }

        @Override
        public AgentLlmResponse chat(List<AgentMessage> messages, List<AgentTool> tools) {
            callCount++;
            lastMessages = List.copyOf(messages);
            lastTools = List.copyOf(tools);
            if (!script.isEmpty()) {
                return script.poll();
            }
            if (fallback != null) {
                return fallback;
            }
            throw new AssertionError("剧本用完了但循环还在继续 —— 说明刹车没生效");
        }
    }

    /** 假工具：记录被调用的参数和上下文，可以设置为"执行就炸" */
    private static class FakeTool implements AgentTool {

        private final String name;
        private final String result;
        private final RuntimeException failure;
        final List<Map<String, Object>> calls = new ArrayList<>();
        final List<AgentContext> contexts = new ArrayList<>();

        private FakeTool(String name, String result, RuntimeException failure) {
            this.name = name;
            this.result = result;
            this.failure = failure;
        }

        static FakeTool returning(String name, String result) {
            return new FakeTool(name, result, null);
        }

        static FakeTool failing(String name, String message) {
            return new FakeTool(name, null, new IllegalStateException(message));
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试用工具：" + name;
        }

        @Override
        public Map<String, Object> parametersSchema() {
            return Map.of("type", "object");
        }

        @Override
        public String execute(AgentContext context, Map<String, Object> arguments) {
            calls.add(arguments);
            contexts.add(context);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    // ==================== 组装 ====================

    private AgentLoopService service(AgentLlmClient llm, AgentTool... tools) {
        return service(llm, 10, tools);
    }

    private AgentLoopService service(AgentLlmClient llm, int maxSteps, AgentTool... tools) {
        AgentProperties properties = new AgentProperties();
        properties.setMaxSteps(maxSteps);
        return new AgentLoopService(llm, new ToolRegistry(List.of(tools)), properties);
    }

    private static AgentLlmResponse call(String id, String toolName, String argumentsJson) {
        return AgentLlmResponse.calls(List.of(new AgentToolCall(id, toolName, argumentsJson)));
    }

    // ==================== 出口①：正常结束 ====================

    @Test
    @DisplayName("模型调一次工具后给出答案：工具真的被执行，循环在第 2 步结束")
    void 调一次工具后正常结束() {
        FakeTool 查人物卡 = FakeTool.returning("查人物卡", "林晚舟：外冷内热的女剑客，惯用左手的短刀");
        FakeLlm llm = new FakeLlm()
                .then(call("call_1", "查人物卡", "{\"name\":\"林晚舟\"}"))
                .then(AgentLlmResponse.answer("林晚舟是一名外冷内热的女剑客。"));

        AgentResult result = service(llm, 查人物卡).run(CONTEXT, "你是墨笺助手", "林晚舟是什么人？");

        // 结论正确、正常结束
        assertThat(result.status()).isEqualTo(AgentResult.Status.FINISHED);
        assertThat(result.succeeded()).isTrue();
        assertThat(result.steps()).isEqualTo(2);
        assertThat(result.answer()).isEqualTo("林晚舟是一名外冷内热的女剑客。");

        // 工具真的被调用了，而且参数被正确解析成了 Map（不是一串 JSON 文本）
        assertThat(查人物卡.calls).hasSize(1);
        assertThat(查人物卡.calls.get(0)).containsEntry("name", "林晚舟");

        // 留下了可追溯的记录
        assertThat(result.trace()).hasSize(1);
        assertThat(result.trace().get(0).toolName()).isEqualTo("查人物卡");
        assertThat(result.trace().get(0).result()).contains("女剑客");
    }

    @Test
    @DisplayName("业务上下文由代码注入到工具：模型全程看不到 projectId")
    void 工具能拿到业务上下文() {
        FakeTool 查人物卡 = FakeTool.returning("查人物卡", "结果");
        FakeLlm llm = new FakeLlm()
                .then(call("c1", "查人物卡", "{}"))
                .then(AgentLlmResponse.answer("好了"));

        service(llm, 查人物卡).run(CONTEXT, "系统提示词", "问题");

        // 工具拿到了项目上下文
        assertThat(查人物卡.contexts).containsExactly(CONTEXT);
        // 但模型收到的消息里，从头到尾没有任何一条泄露 projectId
        assertThat(llm.lastMessages)
                .noneMatch(m -> m.content() != null && m.content().contains("1001"));
    }

    @Test
    @DisplayName("包裹会一轮轮长大：第 2 轮时模型能看到 4 条消息（含工具结果）")
    void 上下文随轮次增长() {
        FakeTool 查人物卡 = FakeTool.returning("查人物卡", "结果");
        FakeLlm llm = new FakeLlm()
                .then(call("call_1", "查人物卡", "{}"))
                .then(AgentLlmResponse.answer("好了"));

        service(llm, 查人物卡).run(CONTEXT, "系统提示词", "用户问题");

        // 第 1 轮：system + user = 2 条
        // 第 2 轮：system + user + assistant(带 tool_calls) + tool(结果) = 4 条
        assertThat(llm.callCount).isEqualTo(2);
        assertThat(llm.lastMessages).hasSize(4);
        assertThat(llm.lastMessages.get(3).role()).isEqualTo("tool");
        assertThat(llm.lastMessages.get(3).content()).isEqualTo("结果");
        // 工具结果必须带上 toolCallId，否则模型对不上是哪个调用的结果
        assertThat(llm.lastMessages.get(3).toolCallId()).isEqualTo("call_1");
    }

    @Test
    @DisplayName("工具清单会随请求发给模型：模型是靠说明书决定调哪个的")
    void 工具清单随请求发出() {
        FakeTool 查人物卡 = FakeTool.returning("查人物卡", "结果");
        FakeTool 查设定 = FakeTool.returning("查设定", "结果");
        FakeLlm llm = new FakeLlm().then(AgentLlmResponse.answer("不用查"));

        service(llm, 查人物卡, 查设定).run(CONTEXT, "系统提示词", "问题");

        assertThat(llm.lastTools).extracting(AgentTool::name)
                .containsExactly("查人物卡", "查设定");
    }

    @Test
    @DisplayName("一轮里要求调两个工具：按顺序都执行，不丢不错")
    void 一轮内多个工具按顺序执行() {
        FakeTool 查人物卡 = FakeTool.returning("查人物卡", "人物资料");
        FakeTool 查设定 = FakeTool.returning("查设定", "世界观设定");
        FakeLlm llm = new FakeLlm()
                .then(AgentLlmResponse.calls(List.of(
                        new AgentToolCall("c1", "查人物卡", "{\"name\":\"林晚舟\"}"),
                        new AgentToolCall("c2", "查设定", "{\"keyword\":\"剑宗\"}"))))
                .then(AgentLlmResponse.answer("综合以上，可以开始写了。"));

        AgentResult result = service(llm, 查人物卡, 查设定).run(CONTEXT, "你是墨笺助手", "准备写林晚舟的出场");

        assertThat(查人物卡.calls).hasSize(1);
        assertThat(查设定.calls).hasSize(1);
        assertThat(result.trace()).extracting(AgentStep::toolName)
                .containsExactly("查人物卡", "查设定");
        assertThat(result.trace().get(1).result()).isEqualTo("世界观设定");
    }

    // ==================== 出口②：刹车 ====================

    @Test
    @DisplayName("模型一直要调工具停不下来：转到最大步数被刹住，不会无限跑")
    void 到达最大步数被刹住() {
        FakeTool 查人物卡 = FakeTool.returning("查人物卡", "还是没有我要的");
        FakeLlm llm = new FakeLlm().thenAlways(call("call_x", "查人物卡", "{}"));

        AgentResult result = service(llm, 3, 查人物卡).run(CONTEXT, "你是墨笺助手", "写一章");

        assertThat(result.status()).isEqualTo(AgentResult.Status.MAX_STEPS);
        assertThat(result.succeeded()).isFalse();
        assertThat(result.steps()).isEqualTo(3);
        // 关键：模型只被问了 3 次，第 4 次不会再发生
        assertThat(llm.callCount).isEqualTo(3);
        // 兜底话术要告诉用户发生了什么并给建议，而不是返回空白
        assertThat(result.answer()).contains("3 步").contains("更具体");
    }

    // ==================== 意外情况：错误回传 ====================

    @Test
    @DisplayName("模型要求调用一个不存在的工具：把错误回传给模型，循环不崩")
    void 调用不存在的工具() {
        FakeTool 查人物卡 = FakeTool.returning("查人物卡", "结果");
        FakeLlm llm = new FakeLlm()
                .then(call("c1", "查天气", "{}"))
                .then(AgentLlmResponse.answer("抱歉，我查不了天气。"));

        AgentResult result = service(llm, 查人物卡).run(CONTEXT, "你是墨笺助手", "今天天气如何");

        // 不抛异常，正常结束
        assertThat(result.succeeded()).isTrue();
        assertThat(result.trace().get(0).result()).contains("没有名为").contains("查人物卡");
        // 错误信息被回传给了模型，让它有机会自己纠正
        assertThat(llm.lastMessages)
                .anyMatch(m -> "tool".equals(m.role()) && m.content().contains("没有名为"));
    }

    @Test
    @DisplayName("工具执行时抛异常：把错误回传给模型，循环不崩")
    void 工具执行异常() {
        FakeTool 查人物卡 = FakeTool.failing("查人物卡", "数据库连接超时");
        FakeLlm llm = new FakeLlm()
                .then(call("c1", "查人物卡", "{}"))
                .then(AgentLlmResponse.answer("资料库暂时读不到，我先按已有信息写。"));

        AgentResult result = service(llm, 查人物卡).run(CONTEXT, "你是墨笺助手", "写一章");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.trace().get(0).result()).contains("执行失败").contains("数据库连接超时");
        assertThat(llm.lastMessages)
                .anyMatch(m -> "tool".equals(m.role()) && m.content().contains("执行失败"));
    }

    @Test
    @DisplayName("模型给出非法 JSON 参数：把错误回传给模型，循环不崩")
    void 参数不是合法JSON() {
        FakeTool 查人物卡 = FakeTool.returning("查人物卡", "结果");
        FakeLlm llm = new FakeLlm()
                .then(call("c1", "查人物卡", "{这不是JSON"))
                .then(AgentLlmResponse.answer("我重新组织一下参数。"));

        AgentResult result = service(llm, 查人物卡).run(CONTEXT, "你是墨笺助手", "查一下");

        assertThat(result.succeeded()).isTrue();
        // 工具没有被执行（参数没解析成功），但循环没崩
        assertThat(查人物卡.calls).isEmpty();
        assertThat(result.trace().get(0).result()).contains("参数不是合法 JSON");
    }

    @Test
    @DisplayName("工具名重名：装配阶段就直接报错，不让它带病上线")
    void 工具重名直接失败() {
        FakeTool a = FakeTool.returning("查人物卡", "A");
        FakeTool b = FakeTool.returning("查人物卡", "B");

        Assertions.assertThatThrownBy(() -> new ToolRegistry(List.of(a, b)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工具名重复");
    }
}
