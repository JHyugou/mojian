package com.mojian.agent.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 工具登记处 —— 负责回答两个问题："有哪些工具能用"、"某个名字对应哪个工具"。
 *
 * <p>Spring 会把容器里所有 {@link AgentTool} 实现自动注入进来，
 * 所以<b>新增一个工具只需要写一个类加 @Component，不用改这里</b>。
 *
 * <p>构造函数直接接收工具列表（而不是自己去扫描），单元测试里一行代码就能塞进假工具。
 */
@Component
public class ToolRegistry {

    /**
     * OpenAI tools 规范：函数名必须匹配 {@code ^[a-zA-Z0-9_-]+$}，最长 64 字符。
     *
     * <p>这个约束是真实踩过的坑：工具名用中文时，请求会被服务方以 400 拒绝
     * （{@code Invalid 'tools[0].function.name': string does not match pattern}）。
     * 放在装配阶段校验，是为了让这类错误在<b>启动时</b>就暴露，而不是等用户点了才炸。
     * 中文说明请写进 {@link AgentTool#description()}。
     */
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> toolBeans) {
        for (AgentTool tool : toolBeans) {
            if (tool.name() == null || !TOOL_NAME_PATTERN.matcher(tool.name()).matches()) {
                throw new IllegalStateException("工具名不符合 OpenAI tools 规范: 「" + tool.name()
                        + "」——只允许字母/数字/下划线/连字符且不超过 64 字符（中文会被服务方拒绝，"
                        + "中文说明请写进 description）");
            }
            AgentTool previous = tools.put(tool.name(), tool);
            if (previous != null) {
                // 工具名是模型唯一的定位依据，重名会导致"以为调了 A 其实调了 B"，必须尽早炸出来
                throw new IllegalStateException("工具名重复: " + tool.name());
            }
        }
    }

    /** 全部工具，顺序与装配顺序一致（发给模型的清单顺序会影响它的选择偏好，保持稳定） */
    public List<AgentTool> all() {
        return List.copyOf(tools.values());
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public int size() {
        return tools.size();
    }
}
