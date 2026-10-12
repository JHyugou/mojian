package com.mojian.share.controller;

import com.mojian.common.api.ApiResponse;
import com.mojian.share.ShareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 分享中心接口（FR-022）：项目级与素材级的 JSON 导入导出。
 * 所有导出内容为白名单字段，不含密钥与审计信息。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ShareController {

    private final ShareService shareService;

    // ==================== 项目级 ====================

    /** 整本小说全量导出（人物含演变节点 + 设定 + 蓝图树 + 全部章节与记忆） */
    @GetMapping("/projects/{projectId}/export")
    public ApiResponse<Map<String, Object>> exportProject(@PathVariable Long projectId) {
        return ApiResponse.ok(shareService.exportProject(projectId));
    }

    /** 导入整本项目：创建全新项目承载 */
    @PostMapping("/projects/import")
    public ApiResponse<Map<String, Object>> importProject(@RequestBody Map<String, Object> payload) {
        return ApiResponse.ok(shareService.importProject(payload));
    }

    // ==================== 素材级：人物 ====================

    /** ids 为空 = 导出全部；结果按创建顺序 */
    @PostMapping("/projects/{projectId}/characters/export")
    public ApiResponse<List<Map<String, Object>>> exportCharacters(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Object> body) {
        List<Object> rawIds = body == null ? null : asList(body.get("ids"));
        return ApiResponse.ok(shareService.exportCharacters(projectId, toLongList(rawIds)));
    }

    /** 追加导入人物（含演变节点），不动现有数据 */
    @PostMapping("/projects/{projectId}/characters/import")
    public ApiResponse<Map<String, Object>> importCharacters(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> payload) {
        int imported = shareService.importCharacters(projectId, payload);
        return ApiResponse.ok(Map.of("imported", imported));
    }

    // ==================== 素材级：世界观设定 ====================

    @PostMapping("/projects/{projectId}/settings/export")
    public ApiResponse<List<Map<String, Object>>> exportSettings(
            @PathVariable Long projectId,
            @RequestBody(required = false) Map<String, Object> body) {
        List<Object> rawIds = body == null ? null : asList(body.get("ids"));
        return ApiResponse.ok(shareService.exportSettings(projectId, toLongList(rawIds)));
    }

    @PostMapping("/projects/{projectId}/settings/import")
    public ApiResponse<Map<String, Object>> importSettings(
            @PathVariable Long projectId,
            @RequestBody Map<String, Object> payload) {
        int imported = shareService.importSettings(projectId, payload);
        return ApiResponse.ok(Map.of("imported", imported));
    }

    // ==================== 项目级：蓝图（不单独导出，只随项目导出携带） ====================

    private List<Object> asList(Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : null;
    }

    private List<Long> toLongList(List<Object> raw) {
        if (raw == null) {
            return null;
        }
        return raw.stream().map(v -> Long.valueOf(String.valueOf(v))).toList();
    }
}
