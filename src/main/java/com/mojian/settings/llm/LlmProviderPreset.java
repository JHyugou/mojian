package com.mojian.settings.llm;

import java.util.List;

/**
 * 模型服务商预设。
 *
 * <p>为什么要有预设：让作者手填 Base URL 是件不友好的事 ——
 * 那个地址长得像密码，还得记住后缀是 {@code /v1} 还是 {@code /v4}，
 * 填错一个字符的表现是"连不上"，而报错信息不会告诉他少了个斜杠。
 *
 * <h3>模型名为什么不靠"实时拉取"一条路</h3>
 * 本来想的是：不写死模型名，改成向服务商拉（OpenAI 兼容服务都有 {@code GET /models}）。
 * 实测之后发现<b>不是每家都有</b> —— 比如 OpenCode Go 的 {@code /models} 直接返回 404。
 * 所以最终做成三层兜底：
 * <ol>
 *   <li>前端先试"从服务商拉取"（能拉就拉，顺便还验证了 Key）</li>
 *   <li>拉不到就用这里登记的<b>已知可用模型</b></li>
 *   <li>都没有就手动填</li>
 * </ol>
 *
 * <p>登记的原则是<b>宁缺勿滥</b>：只写实际验证过或从官方定价页确认过的。
 * 写一个"看着对但其实不通"的名字，比不写更糟 ——
 * 作者会以为是自己 Key 的问题，白折腾半天。
 */
public final class LlmProviderPreset {

    /**
     * @param id          标识（前端用）
     * @param name        显示名
     * @param baseUrl     OpenAI 兼容接口地址；自定义厂家为空
     * @param consoleUrl  申请 Key 的控制台地址，界面上给个链接省得作者去搜
     * @param description 一句话说明，帮作者选
     * @param models      已知可用的模型（可为空，表示"请从服务商拉取或手动填"）
     */
    public record Preset(String id, String name, String baseUrl,
                         String consoleUrl, String description, List<String> models) {
    }

    private LlmProviderPreset() {
    }

    /**
     * 内置预设。
     *
     * <p>只收录经过实际验证能用的地址 —— 写一个"看着像但其实不对"的地址，
     * 比不写更糟：作者会以为是自己 Key 的问题，白折腾半天。
     */
    public static final List<Preset> PRESETS = List.of(
            new Preset("opencode-go", "OpenCode Go",
                    "https://opencode.ai/inference/go/openai/v1",
                    "https://opencode.ai",
                    "一站式网关：一个 Key 用多家模型（DeepSeek / Qwen / GLM / Kimi 等）",
                    // 来自其定价页（2026-10 读取），非猜测
                    List.of("deepseek-v4.1-flash", "deepseek-v4-pro", "qwen3.8-flash",
                            "qwen3.8-max", "glm-5.3-flash", "glm-5.3", "kimi-k3", "minimax-m3")),
            new Preset("siliconflow", "硅基流动",
                    "https://api.siliconflow.cn/v1",
                    "https://cloud.siliconflow.cn",
                    "国内直连；注册送额度，embedding 模型免费",
                    // 只写实测过的这一个 —— 其它名字没验证过，宁可让用户去拉
                    List.of("deepseek-ai/DeepSeek-V4.1-Flash")),
            new Preset("deepseek", "DeepSeek 官方",
                    "https://api.deepseek.com/v1",
                    "https://platform.deepseek.com",
                    "官方直连，中文写作见长",
                    List.of()),
            new Preset("moonshot", "月之暗面 Kimi",
                    "https://api.moonshot.cn/v1",
                    "https://platform.moonshot.cn",
                    "长上下文见长，适合整本书塞进去",
                    List.of()),
            new Preset("zhipu", "智谱 GLM",
                    "https://open.bigmodel.cn/api/paas/v4",
                    "https://open.bigmodel.cn",
                    "GLM 系列",
                    List.of()),
            new Preset("dashscope", "阿里通义千问",
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "https://bailian.console.aliyun.com",
                    "通义千问系列",
                    List.of()),
            new Preset("custom", "自定义（OpenAI 兼容）",
                    "", "",
                    "任何兼容 /chat/completions 的服务，地址自己填",
                    List.of())
    );
}
