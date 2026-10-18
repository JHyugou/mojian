package com.mojian.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.mojian.agent.tool.AgentTool;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.llm.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
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

    // ==================== 流式解析：reasoning 字段兼容 ====================

    /** 用假的 SSE 文本直接喂 parseStream，不联网、不花钱 */
    private AgentLlmResponse parseSse(String sse, List<String> reasoningSink) throws Exception {
        OpenAiCompatAgentLlmClient client = new OpenAiCompatAgentLlmClient(new LlmProperties(), null);
        LlmStreamListener listener = new LlmStreamListener() {
            @Override
            public void onReasoning(String delta) {
                if (reasoningSink != null) reasoningSink.add(delta);
            }
        };
        return client.parseStream(new BufferedReader(new StringReader(sse)), listener, Map.of());
    }

    private static String sseLine(String json) {
        return "data: " + json + "\n";
    }

    @Test
    @DisplayName("DeepSeek 系 reasoning_content：思考过程要回调给界面")
    void 解析思考过程_reasoning_content() throws Exception {
        List<String> reasoning = new ArrayList<>();
        String sse = sseLine("{\"choices\":[{\"delta\":{\"reasoning_content\":\"先想\"}}]}")
                + sseLine("{\"choices\":[{\"delta\":{\"reasoning_content\":\"再看\"}}]}")
                + sseLine("{\"choices\":[{\"delta\":{\"content\":\"答案\"}}]}")
                + sseLine("[DONE]");

        AgentLlmResponse response = parseSse(sse, reasoning);

        assertThat(reasoning).containsExactly("先想", "再看");
        assertThat(response.content()).isEqualTo("答案");
    }

    @Test
    @DisplayName("OpenRouter 等网关用 reasoning 字段：也要认（设置里开了思维链才有东西显示）")
    void 解析思考过程_reasoning别名() throws Exception {
        List<String> reasoning = new ArrayList<>();
        String sse = sseLine("{\"choices\":[{\"delta\":{\"reasoning\":\"它在想\"}}]}")
                + sseLine("{\"choices\":[{\"delta\":{\"content\":\"好\"}}]}");

        AgentLlmResponse response = parseSse(sse, reasoning);

        assertThat(reasoning).containsExactly("它在想");
        assertThat(response.content()).isEqualTo("好");
    }

    @Test
    @DisplayName("流式工具调用分片按 index 归并，参数拼完整")
    void 解析流式工具调用() throws Exception {
        String sse = sseLine("{\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"save_chapter_draft\",\"arguments\":\"{\\\"content\\\":\\\"第一\"}}]}}]}")
                + sseLine("{\"choices\":[{\"delta\":{\"tool_calls\":["
                + "{\"index\":0,\"function\":{\"arguments\":\"段\\\"}\"}}]}}]}")
                + sseLine("{\"choices\":[{\"finish_reason\":\"tool_calls\"}]}");

        AgentLlmResponse response = parseSse(sse, null);

        assertThat(response.wantsTools()).isTrue();
        assertThat(response.toolCalls()).hasSize(1);
        assertThat(response.toolCalls().get(0).name()).isEqualTo("save_chapter_draft");
        assertThat(response.toolCalls().get(0).arguments()).contains("第一段");
    }

    @Test
    @DisplayName("服务端发 error 帧：明确报错，而不是当成空回答正常结束")
    void 流式错误帧() {
        String sse = sseLine("{\"error\":{\"message\":\"quota exceeded\"}}");

        assertThatThrownBy(() -> parseSse(sse, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("quota exceeded");
    }

    @Test
    @DisplayName("返回的不是 SSE（网关缓冲成 JSON）：明确报错，而不是静默空响应")
    void 非流式响应误入流式通道() {
        assertThatThrownBy(() -> parseSse("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是流式响应");
    }
}
