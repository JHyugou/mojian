package com.mojian.generation.vo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mojian.generation.entity.GenerationTask;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 生成任务视图：含结果文本（剧情方案/记忆草稿）与上下文快照（FR-005 展示实际使用的资料）。
 */
@Data
public class GenerationTaskVO {

    private Long id;

    private Long chapterId;

    private String taskType;

    private String status;

    private String errorMessage;

    private String result;

    private JsonNode input;

    private JsonNode context;

    private LocalDateTime createdAt;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    public static GenerationTaskVO from(GenerationTask task, ObjectMapper objectMapper) {
        GenerationTaskVO vo = new GenerationTaskVO();
        vo.setId(task.getId());
        vo.setChapterId(task.getChapterId());
        vo.setTaskType(task.getTaskType());
        vo.setStatus(task.getStatus());
        vo.setErrorMessage(task.getErrorMessage());
        vo.setResult(task.getResultSnapshot());
        vo.setCreatedAt(task.getCreatedAt());
        vo.setStartedAt(task.getStartedAt());
        vo.setFinishedAt(task.getFinishedAt());
        vo.setInput(readTree(objectMapper, task.getInputSnapshot()));
        vo.setContext(readTree(objectMapper, task.getContextSnapshot()));
        return vo;
    }

    private static JsonNode readTree(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
