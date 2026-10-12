package com.mojian.chapter.controller;

import com.mojian.chapter.service.ChapterService;
import com.mojian.chapter.vo.BookVO;
import com.mojian.common.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 成书接口（FR-020）：按创建顺序装配全书，供阅读视图与前端导出。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BookController {

    private final ChapterService chapterService;

    @GetMapping("/projects/{projectId}/book")
    public ApiResponse<BookVO> book(@PathVariable Long projectId) {
        return ApiResponse.ok(chapterService.book(projectId));
    }
}
