package com.mojian.world.setting.controller;

import com.mojian.common.api.ApiResponse;
import com.mojian.world.setting.dto.SettingSaveRequest;
import com.mojian.world.setting.entity.SettingEntry;
import com.mojian.world.setting.service.SettingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class SettingController {

    private final SettingService settingService;

    @GetMapping("/projects/{projectId}/settings")
    public ApiResponse<List<SettingEntry>> list(@PathVariable Long projectId,
                                                @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(settingService.list(projectId, keyword));
    }

    @PostMapping("/projects/{projectId}/settings")
    public ApiResponse<SettingEntry> create(@PathVariable Long projectId,
                                            @Valid @RequestBody SettingSaveRequest request) {
        return ApiResponse.ok(settingService.create(projectId, request));
    }

    @PutMapping("/settings/{settingId}")
    public ApiResponse<SettingEntry> update(@PathVariable Long settingId,
                                            @Valid @RequestBody SettingSaveRequest request) {
        return ApiResponse.ok(settingService.update(settingId, request));
    }

    @DeleteMapping("/settings/{settingId}")
    public ApiResponse<Void> delete(@PathVariable Long settingId) {
        settingService.delete(settingId);
        return ApiResponse.ok();
    }
}
