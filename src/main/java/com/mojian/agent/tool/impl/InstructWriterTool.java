package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.material.ChapterIndex;
import com.mojian.agent.tool.AgentTool;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 策划 → 写作的交接工具。
 *
 * <p>策划给出的写作要求直接写入章节的 writingRequirement 字段；
 * 作者在写作面板的「写作要求」弹窗里点「执行写作」才会真正开写。
 * 不再另建一份待执行指令，避免"写作要求"和"指令"两套状态各自为政。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InstructWriterTool implements AgentTool {

    private final ChapterIndex chapterIndex;
    private final ChapterService chapterService;

    @Override
    public String name() {
        return "instruct_writer";
    }

    @Override
    public String description() {
        return "把这一章的写作要求写入章节（人称、视角、篇幅、禁忌、必须出现/不能出现的元素）。"
                + "写入后作者会在写作面板的「写作要求」里看到，并由作者点「执行写作」；不会自动开写。"
                + "chapterNo 是章节序号（从 1 开始，必须已经存在）。"
                + "instruction 要写成可直接执行的要点，不要写『按蓝图来』这种空话。";
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
                                "description", "给写作 Agent 的具体写作要求"),
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
                    + " 章。请先用 create_chapter 建出这一章，再写写作要求。";
        }

        Chapter updated = chapterService.updateWritingRequirement(chapter.get().getId(), instruction);
        String title = StringUtils.hasText(updated.getTitle())
                ? updated.getTitle().trim() : "第 " + chapterNo + " 章";
        log.info("策划写入第 {} 章写作要求（项目 {}，章节 {}，理由：{}）",
                chapterNo, projectId, updated.getId(), text(arguments.get("reason")));
        return "已把写作要求写入第 " + chapterNo + " 章《" + title + "》。\n"
                + "作者打开写作面板的「写作要求」即可看到，点「执行写作」后开始；不会自动开写。\n"
                + "请告诉作者：要求已写好，执行按钮在写作要求弹窗里。";
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
