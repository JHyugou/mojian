package com.mojian.agent.service;

import com.mojian.agent.entity.ChapterSupplement;
import com.mojian.agent.mapper.ChapterSupplementMapper;
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

    public void markApplied(List<ChapterSupplement> supplements) {
        supplementMapper.markApplied(supplements.stream().map(ChapterSupplement::getId).toList());
    }
}
