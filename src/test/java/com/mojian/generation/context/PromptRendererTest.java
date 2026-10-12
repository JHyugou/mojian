package com.mojian.generation.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PromptRenderer 纯函数单测：人物设定大框与对话示例注入提示词的形态与可空性。
 */
class PromptRendererTest {

    private final PromptRenderer renderer = new PromptRenderer();

    private ContextSnapshot.CharacterBrief brief(String description) {
        ContextSnapshot.CharacterBrief c = new ContextSnapshot.CharacterBrief();
        c.setName("琳瑶");
        c.setRoleIdentity("客栈少东家出身的剑修");
        c.setDescription(description);
        c.setExampleDialogue("等雪停。或者，等有人先忍不住。");
        return c;
    }

    private ContextSnapshot snapshotWith(ContextSnapshot.CharacterBrief character) {
        ContextSnapshot snapshot = new ContextSnapshot();
        snapshot.setProjectName("山河故人");
        snapshot.setChapterTitle("夜雨客栈");
        snapshot.setCharacters(List.of(character));
        return snapshot;
    }

    @Test
    void descriptionInjectedWhenPresent() {
        String prompt = renderer.userPrompt(
                snapshotWith(brief("外貌：青衫仗剑；能力：筑基后期，擅御剑；状态：身中寒毒")), "CONTENT");

        assertTrue(prompt.contains("【人物档案】"), "人物档案段落存在");
        assertTrue(prompt.contains("设定：外貌：青衫仗剑"), "设定大框必须整体注入");
        assertTrue(prompt.contains("对话示例：等雪停"), "对话示例独立注入");
        assertTrue(prompt.indexOf("设定：") > prompt.indexOf("身份："), "设定跟随身份之后");
    }

    @Test
    void absentDescriptionLeavesNoTrace() {
        String prompt = renderer.userPrompt(snapshotWith(brief(null)), "CONTINUE");

        assertFalse(prompt.contains("设定："), "未填设定时不得出现空的设定段");
        assertTrue(prompt.contains("身份：客栈少东家出身的剑修"));
    }
}
