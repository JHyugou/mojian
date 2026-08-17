package com.mojian.chapter.controller;

import com.mojian.chapter.dto.ChapterMemorySaveRequest;
import com.mojian.chapter.dto.ChapterSaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.entity.ChapterMemory;
import com.mojian.chapter.service.ChapterService;
import com.mojian.common.api.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ChapterController {

    private final ChapterService chapterService;

    @GetMapping("/projects/{projectId}/chapters")
    public ApiResponse<List<Chapter>> list(@PathVariable Long projectId) {
        return ApiResponse.ok(chapterService.list(projectId));
    }

    @PostMapping("/projects/{projectId}/chapters")
    public ApiResponse<Chapter> create(@PathVariable Long projectId,
                                       @Valid @RequestBody ChapterSaveRequest request) {
        return ApiResponse.ok(chapterService.create(projectId, request));
    }

    @GetMapping("/chapters/{chapterId}")
    public ApiResponse<Chapter> get(@PathVariable Long chapterId) {
        return ApiResponse.ok(chapterService.get(chapterId));
    }

    @PutMapping("/chapters/{chapterId}")
    public ApiResponse<Chapter> update(@PathVariable Long chapterId,
                                       @Valid @RequestBody ChapterSaveRequest request) {
        return ApiResponse.ok(chapterService.update(chapterId, request));
    }

    @DeleteMapping("/chapters/{chapterId}")
    public ApiResponse<Void> delete(@PathVariable Long chapterId) {
        chapterService.delete(chapterId);
        return ApiResponse.ok();
    }

    @GetMapping("/chapters/{chapterId}/memory")
    public ApiResponse<ChapterMemory> getMemory(@PathVariable Long chapterId) {
        return ApiResponse.ok(chapterService.getMemory(chapterId));
    }

    @PutMapping("/chapters/{chapterId}/memory")
    public ApiResponse<ChapterMemory> saveMemory(@PathVariable Long chapterId,
                                                 @RequestBody ChapterMemorySaveRequest request) {
        return ApiResponse.ok(chapterService.saveMemory(chapterId, request));
    }
}
