package com.mojian.outline.controller;

import com.mojian.common.api.ApiResponse;
import com.mojian.outline.dto.OutlineNodeSaveRequest;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import com.mojian.outline.vo.OutlineNodeVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OutlineController {

    private final OutlineService outlineService;

    @GetMapping("/projects/{projectId}/outline")
    public ApiResponse<List<OutlineNodeVO>> tree(@PathVariable Long projectId) {
        return ApiResponse.ok(outlineService.tree(projectId));
    }

    @PostMapping("/projects/{projectId}/outline/nodes")
    public ApiResponse<PlotOutline> create(@PathVariable Long projectId,
                                           @Valid @RequestBody OutlineNodeSaveRequest request) {
        return ApiResponse.ok(outlineService.create(projectId, request));
    }

    @PutMapping("/outline/nodes/{nodeId}")
    public ApiResponse<PlotOutline> update(@PathVariable Long nodeId,
                                           @Valid @RequestBody OutlineNodeSaveRequest request) {
        return ApiResponse.ok(outlineService.update(nodeId, request));
    }

    @DeleteMapping("/outline/nodes/{nodeId}")
    public ApiResponse<Void> delete(@PathVariable Long nodeId) {
        outlineService.delete(nodeId);
        return ApiResponse.ok();
    }
}
