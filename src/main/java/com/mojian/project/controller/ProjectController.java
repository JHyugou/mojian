package com.mojian.project.controller;

import com.mojian.common.api.ApiResponse;
import com.mojian.project.dto.ProjectSaveRequest;
import com.mojian.project.entity.StoryProject;
import com.mojian.project.service.ProjectService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService projectService;

    @GetMapping("/projects")
    public ApiResponse<List<StoryProject>> list() {
        return ApiResponse.ok(projectService.list());
    }

    @PostMapping("/projects")
    public ApiResponse<StoryProject> create(@Valid @RequestBody ProjectSaveRequest request) {
        return ApiResponse.ok(projectService.create(request));
    }

    @GetMapping("/projects/{projectId}")
    public ApiResponse<StoryProject> get(@PathVariable Long projectId) {
        return ApiResponse.ok(projectService.requireById(projectId));
    }

    @PutMapping("/projects/{projectId}")
    public ApiResponse<StoryProject> update(@PathVariable Long projectId,
                                            @Valid @RequestBody ProjectSaveRequest request) {
        return ApiResponse.ok(projectService.update(projectId, request));
    }

    @DeleteMapping("/projects/{projectId}")
    public ApiResponse<Void> delete(@PathVariable Long projectId) {
        projectService.delete(projectId);
        return ApiResponse.ok();
    }
}
