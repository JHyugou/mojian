package com.mojian.agent.service;

import com.mojian.agent.entity.ChapterDeleteRequest;
import com.mojian.agent.mapper.ChapterDeleteRequestMapper;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 章节删除申请：申请 → 作者确认 → 删除（或自动批准后直接删除）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterDeleteRequestService {

    private final ChapterDeleteRequestMapper requestMapper;
    private final ChapterService chapterService;

    @Transactional
    public ChapterDeleteRequest request(Long projectId, Long conversationId, Chapter chapter, String reason) {
        ChapterDeleteRequest request = new ChapterDeleteRequest();
        request.setProjectId(projectId);
        request.setConversationId(conversationId);
        request.setChapterId(chapter.getId());
        request.setChapterTitle(chapter.getTitle());
        request.setReason(StringUtils.hasText(reason) ? reason.trim() : null);
        request.setStatus("PENDING");
        request.setSource("WRITER");
        requestMapper.insert(request);
        log.info("写作 Agent 申请删除章节 {}「{}」：{}", chapter.getId(), chapter.getTitle(), reason);
        return request;
    }

    public List<ChapterDeleteRequest> recent(Long projectId, int limit) {
        return requestMapper.selectRecent(projectId, limit);
    }

    public List<ChapterDeleteRequest> pending(Long projectId) {
        return requestMapper.selectPending(projectId);
    }

    public ChapterDeleteRequest require(Long requestId) {
        ChapterDeleteRequest request = requestMapper.selectById(requestId);
        if (request == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "删除申请不存在: " + requestId);
        }
        return request;
    }

    @Transactional
    public ChapterDeleteRequest approve(Long requestId) {
        ChapterDeleteRequest request = require(requestId);
        if (!"PENDING".equals(request.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该删除申请已经处理过了（当前状态：" + request.getStatus() + "）");
        }
        chapterService.delete(request.getChapterId());
        request.setStatus("APPROVED");
        requestMapper.updateById(request);
        log.info("作者批准删除章节 {}（申请 {}）", request.getChapterId(), requestId);
        return request;
    }

    @Transactional
    public ChapterDeleteRequest reject(Long requestId) {
        ChapterDeleteRequest request = require(requestId);
        if (!"PENDING".equals(request.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "该删除申请已经处理过了（当前状态：" + request.getStatus() + "）");
        }
        request.setStatus("REJECTED");
        requestMapper.updateById(request);
        log.info("作者拒绝删除章节 {}（申请 {}）", request.getChapterId(), requestId);
        return request;
    }

    /** 自动批准：记录 APPROVED 后直接删除章节 */
    @Transactional
    public ChapterDeleteRequest approveNow(ChapterDeleteRequest request) {
        chapterService.delete(request.getChapterId());
        request.setStatus("APPROVED");
        requestMapper.updateById(request);
        log.warn("自动批准删除章节 {}「{}」：{}", request.getChapterId(),
                request.getChapterTitle(), request.getReason());
        return request;
    }
}
