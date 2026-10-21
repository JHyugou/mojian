package com.mojian.agent.material;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.mapper.ChapterMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 章节编号的唯一口径。
 *
 * <p><b>为什么必须抽出来共用</b>：材料里渲染成"第 12 章"，工具按"第 12 章"去取原文，
 * 如果两边各写一套排序逻辑，一旦不一致，模型嘴里的"第 12 章"和它实际拿到的章节
 * 就是两章 —— 这种错不会报异常，只会表现为"Agent 说的和给的对不上"，
 * 极难倒查。所以编号口径只允许有一处实现。
 */
@Component
@RequiredArgsConstructor
public class ChapterIndex {

    private final ChapterMapper chapterMapper;

    /**
     * 章节正序（第 1 章在前）。
     *
     * <p>这里直接用 mapper 而不是 {@code ChapterService.list()}：后者刻意不加载正文大字段
     * （列表页不需要），而 Agent 侧恰恰需要正文。
     */
    public List<Chapter> ordered(Long projectId) {
        List<Chapter> chapters = chapterMapper.selectList(new LambdaQueryWrapper<Chapter>()
                .eq(Chapter::getProjectId, projectId));
        chapters.sort(Comparator.comparing(Chapter::getCreatedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return chapters;
    }

    /** 按序号取章（从 1 开始）。序号非法或不存在的章返回空，由调用方给出可读提示 */
    public Optional<Chapter> byNumber(Long projectId, int chapterNo) {
        List<Chapter> chapters = ordered(projectId);
        if (chapterNo < 1 || chapterNo > chapters.size()) {
            return Optional.empty();
        }
        return Optional.of(chapters.get(chapterNo - 1));
    }

    /** 章节所在序号（从 1 开始）；找不到返回 0。 */
    public int numberOf(Long projectId, Long chapterId) {
        if (chapterId == null) return 0;
        List<Chapter> chapters = ordered(projectId);
        for (int i = 0; i < chapters.size(); i++) {
            if (chapterId.equals(chapters.get(i).getId())) {
                return i + 1;
            }
        }
        return 0;
    }

    public int count(Long projectId) {
        return ordered(projectId).size();
    }
}
