package com.mojian.agent.service;

import com.mojian.chapter.dto.ChapterMemorySaveRequest;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import com.mojian.generation.llm.LlmClient;
import com.mojian.generation.llm.LlmRequest;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 章节记忆自动提炼。
 *
 * <p>为什么必须有它：长篇创作里最贵的信息是"前情"。之前章节记忆只能靠作者
 * 在面板里手动点一次 AI 提炼 —— 忘了点，后面的写作就失去了连续性。
 * 现在写作任务成功落库后会自动在后台提炼一次并写回 {@code chapter_memory}，
 * 作者仍可以在界面上修改覆盖。
 *
 * <p>用独立执行器（单线程）而不是写作线程池：它是"写完之后的收尾工作"，
 * 不该占用写作任务的并发额度；失败也只记日志，不影响正文。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterMemoryService {

    private static final String SYSTEM_PROMPT = """
            你是长篇小说编辑，负责为某一章提炼"前情记忆"，供后续章节的写作参考。
            只输出记忆文本，不要寒暄、不要解释你在做什么、不要评价这一章。

            按下面四段组织（每段都要有，信息不足就写"无"）：
            【章节摘要】本章发生了什么：按时间顺序写清关键事件与结果
            【重要事实】新出现的人物/地点/道具/设定，以及被揭示的旧信息
            【未解决冲突】还没有收线的悬念、敌意、承诺、欠下的人情
            【人物状态变化】谁的态度/能力/处境/关系发生了变化

            要求：只依据给定材料，不要补充材料里没有的设定；总长度控制在 400~800 字；
            用叙述性中文，不要分点编号，不要出现"本章"以外的章节指代。
            """;

    private final LlmClient llmClient;
    private final ChapterService chapterService;
    private final OutlineService outlineService;

    /**
     * 安静地生成并写回章节记忆。
     *
     * <p>"安静"是刻意的：任何失败都只记日志 —— 作者刚刚写完一章，
     * 最不该看到的是"正文写好了，但记忆没生成"的红字。
     */
    public void generateQuietly(Long projectId, Long chapterId) {
        try {
            Chapter chapter = chapterService.requireById(chapterId);
            String content = chapter.getContent();
            if (!StringUtils.hasText(content)) {
                return;
            }
            String userPrompt = buildPrompt(chapter, content);
            String memory = llmClient.chat(LlmRequest.of(SYSTEM_PROMPT, userPrompt, "CHAPTER_MEMORY"));
            if (!StringUtils.hasText(memory)) {
                log.warn("章节 {} 的记忆提炼返回空内容，跳过写回", chapterId);
                return;
            }
            ChapterMemorySaveRequest request = new ChapterMemorySaveRequest();
            request.setMemory(memory.trim());
            chapterService.saveMemory(chapterId, request);
            log.info("章节 {} 记忆已自动提炼并写回（{} 字）", chapterId, memory.trim().length());
        } catch (Exception e) {
            log.warn("章节 {} 记忆自动提炼失败（不影响正文）: {}", chapterId, e.getMessage());
        }
    }

    private String buildPrompt(Chapter chapter, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("章节标题：").append(StringUtils.hasText(chapter.getTitle()) ? chapter.getTitle() : "未命名").append('\n');
        if (chapter.getOutlineNodeId() != null) {
            try {
                PlotOutline node = outlineService.requireById(chapter.getOutlineNodeId());
                if (StringUtils.hasText(node.getSummary())) {
                    sb.append("本章蓝图：\n").append(node.getSummary().trim()).append('\n');
                }
            } catch (Exception ignored) {
                // 蓝图被删了不影响记忆提炼
            }
        }
        if (StringUtils.hasText(chapter.getWritingRequirement())) {
            sb.append("作者对本章的要求：").append(chapter.getWritingRequirement().trim()).append('\n');
        }
        sb.append("\n本章正文：\n").append(truncate(content));
        return sb.toString();
    }

    /** 超长章节只送首尾：记忆要的是"发生了什么"，中间的过程细节可以牺牲 */
    private String truncate(String content) {
        String trimmed = content.trim();
        int max = 30000;
        if (trimmed.length() <= max) {
            return trimmed;
        }
        String head = trimmed.substring(0, 16000);
        String tail = trimmed.substring(trimmed.length() - 12000);
        return head + "\n……（中间省略）……\n" + tail;
    }
}
