package com.mojian.agent.service;

import com.mojian.agent.entity.WriterInstruction;
import com.mojian.agent.mapper.WriterInstructionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 策划 → 写作 的指令通道。
 *
 * <p>写作 Agent 现在有两个指令来源：作者（直接输入）与策划 Agent（通过工具下发）。
 * 指令先落库、带 PENDING 状态；写作任务开始时读取并注入，成功写入正文后标记已执行。
 * 失败或取消则保持 PENDING，下次重试仍然带着它。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WriterInstructionService {

    private final WriterInstructionMapper instructionMapper;

    public WriterInstruction create(Long projectId, Long chapterId, String content, String reason) {
        WriterInstruction instruction = new WriterInstruction();
        instruction.setProjectId(projectId);
        instruction.setChapterId(chapterId);
        instruction.setContent(content);
        instruction.setReason(StringUtils.hasText(reason) ? reason.trim() : null);
        instruction.setSource("PLANNER");
        instruction.setStatus("PENDING");
        instructionMapper.insert(instruction);
        log.info("策划下发写作指令：项目 {}，章节 {}，指令 {} 字", projectId, chapterId, content.length());
        return instruction;
    }

    /** 写作任务读取：该章所有待执行指令 */
    public List<WriterInstruction> pending(Long projectId, Long chapterId) {
        return instructionMapper.selectPending(projectId, chapterId);
    }

    /** 写作面板展示：最近指令（含已执行），让用户看见两个来源发生过什么 */
    public List<WriterInstruction> recent(Long projectId, int limit) {
        return instructionMapper.selectRecent(projectId, limit);
    }

    public void markConsumed(List<WriterInstruction> instructions) {
        instructionMapper.markConsumed(instructions.stream().map(WriterInstruction::getId).toList());
    }
}
