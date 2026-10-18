package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.entity.WriterInstruction;
import com.mojian.agent.material.ChapterIndex;
import com.mojian.agent.service.WriterInstructionService;
import com.mojian.agent.tool.AgentTool;
import com.mojian.chapter.entity.Chapter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 策划 → 写作 的交接工具。
 *
 * <p>写作 Agent 现在有两个指令来源：作者直接输入，以及策划 Agent 通过本工具下发的提示词。
 * 这份提示词会落库、在写作面板显示，并在写作任务开始时作为必备材料注入。
 * <b>不会自动开写</b>：用户仍然要切到写作模式、点一下开始 —— 花钱的动作应由作者发起。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstructWriterTool implements AgentTool {

    private final ChapterIndex chapterIndex;
    private final WriterInstructionService instructionService;

    @Override
    public String name() {
        return "instruct_writer";
    }

    @Override
    public String description() {
        return "把一段写作提示词交给写作 Agent（针对某一章），由它下次开写时执行。"
                + "当你已经把走向落成蓝图，想明确告诉写作 Agent『这一章怎么写』时使用。"
                + "chapterNo 是章节序号（从 1 开始，必须已经存在）。"
                + "instruction 要写成可执行的具体要求：写哪一段、节奏、视角、必须出现/不能出现的东西、"
                + "与前后章的衔接点。不要只写『按蓝图来』这种空话。"
                + "调用后告诉用户：指令已排队，切到写作模式即可执行（不会自动花钱开写）。";
    }

    @Override
    public List<String> roles() {
        return List.of("PLANNER");
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "chapterNo", Map.of("type", "integer",
                                "description", "目标章节序号，从 1 开始；必须已存在"),
                        "instruction", Map.of("type", "string",
                                "description", "给写作 Agent 的具体写作提示词"),
                        "reason", Map.of("type", "string",
                                "description", "为什么这么写（给作者看的依据）")),
                "required", List.of("chapterNo", "instruction"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Integer chapterNo = intOf(arguments.get("chapterNo"));
        if (chapterNo == null || chapterNo < 1) {
            return "错误：chapterNo 必须是 ≥1 的整数。";
        }
        String instruction = text(arguments.get("instruction"));
        if (!StringUtils.hasText(instruction)) {
            return "错误：instruction 不能为空。";
        }
        if (instruction.length() > 4000) {
            return "错误：instruction 太长（上限 4000 字），请压缩成可执行的要点。";
        }

        Optional<Chapter> chapter = chapterIndex.byNumber(projectId, chapterNo);
        if (chapter.isEmpty()) {
            int total = chapterIndex.count(projectId);
            return "错误：本书目前共 " + total + " 章，没有第 " + chapterNo
                    + " 章。请让作者先在写作模式里创建这一章，再下发指令。";
        }

        WriterInstruction saved = instructionService.create(projectId, chapter.get().getId(),
                instruction, text(arguments.get("reason")));
        String title = StringUtils.hasText(chapter.get().getTitle())
                ? chapter.get().getTitle().trim() : "第 " + chapterNo + " 章";
        log.info("策划下发写作指令 {}（项目 {}，章节 {}）", saved.getId(), projectId, chapter.get().getId());
        return "已把写作指令交给写作 Agent：第 " + chapterNo + " 章｜" + title + "。\n"
                + "它会在下一次写作任务开始时读取这条指令（与作者输入一起生效），"
                + "并在写作面板的对话区显示为『策划 Agent 的指令』。\n"
                + "现在请停下，用两三句话告诉作者你给写作 Agent 交代了什么；"
                + "提醒他切到写作模式、按需补充作者指令后开始写 —— 不会自动开写。";
    }

    private Integer intOf(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
