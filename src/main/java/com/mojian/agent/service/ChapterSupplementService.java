package com.mojian.agent.service;

import com.mojian.agent.entity.ChapterSupplement;
import com.mojian.agent.mapper.ChapterSupplementMapper;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 章节补充要求：策划审稿后追加、写作任务执行、执行后归档为 APPLIED。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterSupplementService {

    private final ChapterSupplementMapper supplementMapper;

    @Transactional
    public ChapterSupplement create(Long projectId, Long chapterId, String content, String reason) {
        ChapterSupplement supplement = new ChapterSupplement();
        supplement.setProjectId(projectId);
        supplement.setChapterId(chapterId);
        supplement.setContent(content.trim());
        supplement.setReason(StringUtils.hasText(reason) ? reason.trim() : null);
        supplement.setSource("PLANNER");
        supplement.setStatus("PENDING");
        supplementMapper.insert(supplement);
        log.info("策划为章节 {} 追加补充要求 {}：{}", chapterId, supplement.getId(), content);
        return supplement;
    }

    public List<ChapterSupplement> listByChapter(Long chapterId) {
        return supplementMapper.selectByChapter(chapterId);
    }

    public List<ChapterSupplement> pending(Long chapterId) {
        return supplementMapper.selectPending(chapterId);
    }

    public ChapterSupplement require(Long supplementId) {
        ChapterSupplement supplement = supplementMapper.selectById(supplementId);
        if (supplement == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "补充要求不存在: " + supplementId);
        }
        return supplement;
    }

    /** 用户明确不要某条补充要求时，整条移除；待执行的不再注入，已应用的历史也一并删掉 */
    @Transactional
    public ChapterSupplement remove(Long projectId, Long supplementId) {
        ChapterSupplement supplement = require(supplementId);
        if (!projectId.equals(supplement.getProjectId())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "该补充要求不属于当前项目，不能移除");
        }
        supplementMapper.deleteById(supplementId);
        log.info("移除章节 {} 的补充要求 {}「{}」", supplement.getChapterId(), supplementId,
                supplement.getContent());
        return supplement;
    }

    public void markApplied(List<ChapterSupplement> supplements) {
        supplementMapper.markApplied(supplements.stream().map(ChapterSupplement::getId).toList());
    }
}
