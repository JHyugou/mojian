package com.mojian.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojian.agent.tool.AgentTool;
import com.mojian.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 协议转换测试 —— 只测"翻译"，不测网络。
 *
 * <p>为什么不测网络：HTTP 那一层薄得没什么可测的，而"内部消息模型 ↔ OpenAI 报文格式"
 * 的翻译才是真正容易错的地方（字段名、嵌套层级、null 处理）。
 * 把它抽成静态方法单独测，又快又不花钱。
 */
class OpenAiCompatAgentLlmClientTest {

    private static class StubTool implements AgentTool {
        private final String name;
        private final String description;
        private final Map<String, Object> schema;

        StubTool(String name, String description, Map<String, Object> schema) {
            this.name = name;
            this.description = description;
            this.schema = schema;
        }

        @Override public String name() { return name; }
        @Override public String description() { return description; }
        @Override public Map<String, Object> parametersSchema() { return schema; }
        @Override public String execute(com.mojian.agent.AgentContext context, Map<String, Object> arguments) { return ""; }
    }

    private static JsonNode parse(String json) throws Exception {
        return OpenAiCompatAgentLlmClient.MAPPER.readTree(json);
    }

    private static final StubTool query_character = new StubTool(
            "query_character",
            "查询本书中人物的设定资料",
            Map.of("type", "object",
                    "properties", Map.of("name", Map.of("type", "string", "description", "人物姓名")),
                    "required", List.of("name")));

    // ==================== 请求方向：内部模型 → OpenAI 报文 ====================

    @Test
    @DisplayName("请求体带上了工具说明书（名称/描述/参数 Schema）")
    void 请求体包含工具说明书() throws Exception {
        String json = OpenAiCompatAgentLlmClient.buildBody(
                "deepseek-v4.1-flash",
                List.of(AgentMessage.system("你是墨笺助手"), AgentMessage.user("林晚舟是谁")),
                List.of(query_character));

        JsonNode root = parse(json);
        assertThat(root.path("model").asText()).isEqualTo("deepseek-v4.1-flash");
        assertThat(root.path("stream").asBoolean()).isFalse();

        JsonNode function = root.path("tools").path(0).path("function");
        assertThat(root.path("tools").path(0).path("type").asText()).isEqualTo("function");
        assertThat(function.path("name").asText()).isEqualTo("query_character");
        assertThat(function.path("description").asText()).contains("设定资料");
        assertThat(function.path("parameters").path("properties").path("name").path("type").asText())
                .isEqualTo("string");
    }

    @Test
    @DisplayName("多轮消息序列化正确：assistant 带 tool_calls、tool 带 tool_call_id")
    void 多轮消息序列化正确() throws Exception {
        List<AgentMessage> messages = List.of(
                AgentMessage.system("你是墨笺助手"),
                AgentMessage.user("林晚舟是谁"),
                AgentMessage.assistant(null, List.of(
                        new AgentToolCall("call_1", "query_character", "{\"name\":\"林晚舟\"}"))),
                AgentMessage.tool("call_1", "林晚舟：外冷内热的女剑客"));

        JsonNode array = parse(OpenAiCompatAgentLlmClient.buildBody("m", messages, List.of()))
                .path("messages");

        assertThat(array).hasSize(4);
        assertThat(array.get(0).path("role").asText()).isEqualTo("system");
        assertThat(array.get(1).path("role").asText()).isEqualTo("user");

        JsonNode assistantCall = array.get(2).path("tool_calls").get(0);
        assertThat(assistantCall.path("id").asText()).isEqualTo("call_1");
        assertThat(assistantCall.path("type").asText()).isEqualTo("function");
        assertThat(assistantCall.path("function").path("name").asText()).isEqualTo("query_character");
        assertThat(assistantCall.path("function").path("arguments").asText()).contains("林晚舟");

        assertThat(array.get(3).path("role").asText()).isEqualTo("tool");
        assertThat(array.get(3).path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(array.get(3).path("content").asText()).isEqualTo("林晚舟：外冷内热的女剑客");
    }

    @Test
    @DisplayName("没有工具时不发 tools 字段（有些服务方对空数组会报错）")
    void 没有工具时不发tools字段() throws Exception {
        String json = OpenAiCompatAgentLlmClient.buildBody(
                "m", List.of(AgentMessage.user("你好")), List.of());
        assertThat(parse(json).has("tools")).isFalse();
    }

    // ==================== 响应方向：OpenAI 报文 → 内部模型 ====================

    @Test
    @DisplayName("解析普通回答：不带工具调用，content 有内容")
    void 解析普通回答() {
        String raw = """
                {"choices":[{"message":{"role":"assistant","content":"林晚舟是一名外冷内热的女剑客。"}}]}
                """;

        AgentLlmResponse response = OpenAiCompatAgentLlmClient.parseResponse(raw);

        assertThat(response.wantsTools()).isFalse();
        assertThat(response.content()).isEqualTo("林晚舟是一名外冷内热的女剑客。");
        assertThat(response.toolCalls()).isEmpty();
    }

    @Test
    @DisplayName("解析工具调用：content 为 null，tool_calls 里有工具名与参数")
    void 解析工具调用() {
        String raw = """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"query_character","arguments":"{\\"name\\":\\"林晚舟\\"}"}}
                ]}}]}
                """;

        AgentLlmResponse response = OpenAiCompatAgentLlmClient.parseResponse(raw);

        assertThat(response.wantsTools()).isTrue();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).id()).isEqualTo("call_1");
        assertThat(response.toolCalls().get(0).name()).isEqualTo("query_character");
        assertThat(response.toolCalls().get(0).arguments()).contains("林晚舟");
    }

    @Test
    @DisplayName("解析一轮里的多个工具调用")
    void 解析多个工具调用() {
        String raw = """
                {"choices":[{"message":{"tool_calls":[
                  {"id":"c1","function":{"name":"query_character","arguments":"{}"}},
                  {"id":"c2","function":{"name":"query_setting","arguments":"{}"}}
                ]}}]}
                """;

        AgentLlmResponse response = OpenAiCompatAgentLlmClient.parseResponse(raw);

        assertThat(response.toolCalls()).extracting(AgentToolCall::name)
                .containsExactly("query_character", "query_setting");
    }

    @Test
    @DisplayName("缺少 arguments 时兜底成空对象，不让下游拿到 null")
    void 缺少参数时兜底() {
        String raw = """
                {"choices":[{"message":{"tool_calls":[{"id":"c1","function":{"name":"query_character"}}]}}]}
                """;

        AgentLlmResponse response = OpenAiCompatAgentLlmClient.parseResponse(raw);

        assertThat(response.toolCalls().get(0).arguments()).isEqualTo("{}");
    }

    @Test
    @DisplayName("返回格式不对时抛业务异常，而不是让 null 扩散到循环里")
    void 返回格式异常() {
        assertThatThrownBy(() -> OpenAiCompatAgentLlmClient.parseResponse("这根本不是 JSON"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("格式异常");
    }
}
