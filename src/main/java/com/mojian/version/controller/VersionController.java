package com.mojian.version.controller;

import com.mojian.common.api.ApiResponse;
import com.mojian.version.dto.RollbackRequest;
import com.mojian.version.entity.ChapterVersion;
import com.mojian.version.service.VersionService;
import com.mojian.version.vo.VersionCompareVO;
import com.mojian.version.vo.VersionVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VersionController {

    private final VersionService versionService;

    @GetMapping("/chapters/{chapterId}/versions")
    public ApiResponse<List<VersionVO>> list(@PathVariable Long chapterId) {
        return ApiResponse.ok(versionService.listVersions(chapterId));
    }

    @GetMapping("/chapter-versions/compare")
    public ApiResponse<VersionCompareVO> compare(@RequestParam Long baseVersionId,
                                                 @RequestParam Long targetVersionId) {
        return ApiResponse.ok(versionService.compare(baseVersionId, targetVersionId));
    }

    @GetMapping("/chapter-versions/{versionId}")
    public ApiResponse<ChapterVersion> get(@PathVariable Long versionId) {
        return ApiResponse.ok(versionService.getVersion(versionId));
    }

    @PostMapping("/chapters/{chapterId}/rollback")
    public ApiResponse<VersionVO> rollback(@PathVariable Long chapterId,
                                           @Valid @RequestBody RollbackRequest request) {
        return ApiResponse.ok(versionService.rollback(chapterId, request));
    }
}
