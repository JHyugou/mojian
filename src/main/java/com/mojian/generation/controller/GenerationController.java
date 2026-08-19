package com.mojian.generation.controller;

import com.mojian.common.api.ApiResponse;
import com.mojian.generation.dto.GenerationTaskRequest;
import com.mojian.generation.dto.PlanGenerationRequest;
import com.mojian.generation.service.GenerationTaskService;
import com.mojian.generation.sse.TaskEventBroker;
import com.mojian.generation.vo.GenerationTaskVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 生成接口（SRS 10.4）：
 * - 两阶段：/generation-plans 出方案，确认后 /generation-tasks 出正文（FR-010）
 * - SSE：/events 订阅片段与状态（FR-015）
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GenerationController {

    private final GenerationTaskService taskService;
    private final TaskEventBroker broker;

    @PostMapping("/chapters/{chapterId}/generation-plans")
    public ApiResponse<GenerationTaskVO> createPlan(@PathVariable Long chapterId,
                                                    @Valid @RequestBody PlanGenerationRequest request) {
        return ApiResponse.ok(taskService.createPlanTask(chapterId, request.getInstruction()));
    }

    @PostMapping("/chapters/{chapterId}/generation-tasks")
    public ApiResponse<GenerationTaskVO> createTask(@PathVariable Long chapterId,
                                                    @Valid @RequestBody GenerationTaskRequest request) {
        return ApiResponse.ok(taskService.createTask(chapterId, request));
    }

    @GetMapping("/generation-tasks/{taskId}")
    public ApiResponse<GenerationTaskVO> getTask(@PathVariable Long taskId) {
        return ApiResponse.ok(taskService.getTask(taskId));
    }

    @GetMapping(value = "/generation-tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable Long taskId) {
        taskService.requireById(taskId);
        return broker.subscribe(taskId);
    }

    @PostMapping("/generation-tasks/{taskId}/retry")
    public ApiResponse<GenerationTaskVO> retry(@PathVariable Long taskId) {
        return ApiResponse.ok(taskService.retry(taskId));
    }
}
