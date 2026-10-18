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
import java.util.Optional;

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

    /** 单独执行某条补充要求：标记 EXECUTING 并挂上任务 id */
    @Transactional
    public void markExecuting(Long supplementId, Long taskId) {
        if (supplementMapper.markExecuting(supplementId, taskId) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "这条补充要求已经在执行或已处理，请刷新后重试");
        }
    }

    /** 任务失败/取消：回到待执行，按钮重新可用 */
    public void resetExecuting(Long supplementId) {
        supplementMapper.resetExecuting(supplementId);
    }

    /** 被任务挂起的补充要求（前端重连时用来判断哪条在执行） */
    public Optional<ChapterSupplement> findByExecutingTask(Long taskId) {
        return Optional.ofNullable(supplementMapper.selectByExecutingTask(taskId));
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

    /**
     * 写作保存成功后：只标记为"已提交、待核验"。
     * 保存新版本不等于要求真的落实，必须由作者/策划看过正文后再确认 APPLIED。
     */
    @Transactional
    public void markSubmitted(List<ChapterSupplement> supplements) {
        supplementMapper.markSubmitted(supplements.stream().map(ChapterSupplement::getId).toList());
        log.info("补充要求已提交待核验：{} 条", supplements.size());
    }

    @Transactional
    public ChapterSupplement verify(Long chapterId, Long supplementId) {
        ChapterSupplement supplement = requireInChapter(chapterId, supplementId);
        if (supplementMapper.markVerified(supplementId) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有待核验的补充要求才能确认落实");
        }
        return require(supplementId);
    }

    @Transactional
    public ChapterSupplement returnToPending(Long chapterId, Long supplementId) {
        ChapterSupplement supplement = requireInChapter(chapterId, supplementId);
        if (supplementMapper.returnToPending(supplementId) == 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有待核验的补充要求才能打回重做");
        }
        return require(supplementId);
    }

    private ChapterSupplement requireInChapter(Long chapterId, Long supplementId) {
        ChapterSupplement supplement = require(supplementId);
        if (!chapterId.equals(supplement.getChapterId())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "这条补充要求不属于当前章节");
        }
        return supplement;
    }
}
