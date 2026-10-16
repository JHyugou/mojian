package com.mojian.agent.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<AgentTool> toolBeans) {
        for (AgentTool tool : toolBeans) {
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
