package com.mojian.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 策划提示词必须把"作者当前的采纳模式"告诉模型。
 *
 * <p>真实 bug：用户开着自动采纳，模型却回复"需要你手动确认"，草稿其实已经被自动写入了。
 */
class AgentPromptsTest {

    @Test
    @DisplayName("自动采纳模式：告诉模型不要再说需要手动确认")
    void 自动采纳模式() {
        String prompt = AgentPrompts.planner("【本书概况】测试", true);
        assertThat(prompt).contains("作者已开启自动采纳");
        assertThat(prompt).contains("自动确认并写入");
        assertThat(prompt).doesNotContain("作者当前是手动采纳");
    }

    @Test
    @DisplayName("手动采纳模式：如实说等待确认")
    void 手动采纳模式() {
        String prompt = AgentPrompts.planner("【本书概况】测试", false);
        assertThat(prompt).contains("作者当前是手动采纳");
        assertThat(prompt).contains("等待你确认");
        assertThat(prompt).doesNotContain("作者已开启自动采纳");
    }
}
