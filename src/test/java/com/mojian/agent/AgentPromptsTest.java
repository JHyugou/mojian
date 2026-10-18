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
    @DisplayName("审稿不是必须提修改：稿子够好时允许零补充要求")
    void 审稿允许零补充要求() {
        String prompt = AgentPrompts.planner("【本书概况】测试", false);
        assertThat(prompt).contains("审稿不是必须提修改");
        assertThat(prompt).contains("零条补充要求是完全正常的结果");
        assertThat(prompt).contains("不要为了证明自己审过而强行提要求");
    }

    @Test
    @DisplayName("审稿通过后允许把章节标记完成")
    void 审稿通过可定稿() {
        String prompt = AgentPrompts.planner("【本书概况】测试", false);
        assertThat(prompt).contains("审稿通过后可以定稿");
        assertThat(prompt).contains("update_chapter_status");
        assertThat(prompt).contains("不要仅凭\"能看\"").contains("强行定稿");
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
