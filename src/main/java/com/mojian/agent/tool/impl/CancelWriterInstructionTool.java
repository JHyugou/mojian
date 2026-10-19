package com.mojian.agent.tool.impl;

import com.mojian.agent.AgentContext;
import com.mojian.agent.entity.WriterInstruction;
import com.mojian.agent.service.WriterInstructionService;
import com.mojian.agent.tool.AgentTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 撤回一条尚未执行的策划写作指令。
 *
 * <p>策划下发指令后，有些会在写作开始前被作者/策划发现冲突。
 * 已执行的记录不能改写，只允许撤回 PENDING 状态。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelWriterInstructionTool implements AgentTool {

    private final WriterInstructionService instructionService;

    @Override
    public String name() {
        return "cancel_writer_instruction";
    }

    @Override
    public String description() {
        return "撤回一条还没有执行的策划写作指令。"
                + "先用 read_writer_conversation 查看指令 ID（PENDING 状态）；"
                + "已执行（CONSUMED）的历史指令不能撤回。"
                + "撤回后下次写作不再注入这条指令，但保留记录供审计。";
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
                        "instructionId", Map.of("type", "integer",
                                "description", "写作指令记录 ID（read_writer_conversation 里可看到）"),
                        "reason", Map.of("type", "string",
                                "description", "撤回理由，可选")),
                "required", List.of("instructionId"));
    }

    @Override
    public String execute(AgentContext context, Map<String, Object> arguments) {
        Long projectId = context.requireProjectId();
        Long instructionId = longOf(arguments.get("instructionId"));
        String reason = text(arguments.get("reason"));
        if (instructionId == null) {
            return "错误：必须提供 instructionId（先用 read_writer_conversation 查到）。";
        }
        WriterInstruction instruction = instructionService.require(instructionId);
        if (!projectId.equals(instruction.getProjectId())) {
            return "错误：这条写作指令不属于当前项目，不能撤回。";
        }
        if (!"PENDING".equals(instruction.getStatus())) {
            return "错误：这条指令当前状态是「" + statusLabel(instruction.getStatus())
                    + "」，只有待执行的指令可以撤回。";
        }
        instructionService.cancel(instructionId);
        log.info("策划撤回写作指令 {}：{}", instructionId, reason);
        return "已撤回写作指令 #" + instructionId + "。"
                + "下一轮写作不会再注入它；记录仍保留为「已撤回」。"
                + (StringUtils.hasText(reason) ? "\n撤回理由：" + reason : "");
    }

    private String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case "PENDING" -> "待执行";
            case "CONSUMED" -> "已执行";
            case "CANCELLED" -> "已撤回";
            default -> status;
        };
    }

    private Long longOf(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
