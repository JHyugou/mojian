package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.entity.ChapterSupplement;
import com.mojian.agent.service.ChapterSupplementService;
import com.mojian.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 策划移除一条审稿补充要求。
 *
 * <p>补充要求是逐条独立的，用户可能看完后说"第二条不要了"。
 * 只有用户明确要求撤销某条时才调用；待执行的移除后不再注入，
 * 已应用的会连同历史一起删掉（审稿工具里会显示状态，调用前要分清）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemoveChapterSupplementTool implements AgentTool {

    private final ChapterSupplementService supplementService;

    @Override
    public String name() {
        return "remove_chapter_supplement";
    }

    @Override
    public String description() {
        return "移除一条章节补充要求（补充要求记录 ID 见 review_chapter 的输出）。"
                + "待执行的移除后写作 Agent 不再执行它；已应用的会连同历史一起删除。"
                + "只有用户明确说不要/删除某条补充要求时才调用；一次删一条，不要自行清理。"
                + "如果用户说不清是哪条，先用 review_chapter 把当前条目列出来让他确认。";
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
                        "supplementId", Map.of("type", "integer",
                                "description", "补充要求记录 ID（review_chapter 输出里的记录 ID）")),
                "required", List.of("supplementId"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Long supplementId = longOf(arguments.get("supplementId"));
        if (supplementId == null) {
            return "错误：supplementId 必须是补充要求记录 ID（整数）。";
        }
        ChapterSupplement removed = supplementService.remove(projectId, supplementId);
        log.warn("策划移除补充要求 {}（章节 {}）：{}", supplementId,
                removed.getChapterId(), removed.getContent());
        return "已移除补充要求（记录 ID " + supplementId + "）："
                + removed.getContent() + "\n"
                + ("PENDING".equals(removed.getStatus())
                ? "它原本是待执行状态，写作 Agent 下次写这一章时不会再看到它。"
                : "它原本是已应用状态，这条历史记录也一并删除了。")
                + "\n请明确告诉用户移除了哪一条。";
    }

    private Long longOf(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
