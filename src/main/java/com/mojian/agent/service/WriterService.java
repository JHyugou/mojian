package com.mojian.agent.service;

import com.mojian.agent.AgentContext;
import com.mojian.agent.AgentLoopService;
import com.mojian.agent.AgentPrompts;
import com.mojian.agent.AgentResult;
import com.mojian.agent.AgentRole;
import com.mojian.agent.AgentStep;
import com.mojian.agent.context.AgentContextAssembly;
import com.mojian.agent.context.AgentContextSection;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.llm.AgentMessage;
import com.mojian.agent.material.ChapterIndex;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 写作 Agent 的一次任务：把某一章的蓝图写成正文。
 *
 * <h3>和策划 Agent 的最大区别：必备材料</h3>
 * 策划的材料全是"可选"（装不下才裁）；写作有两块<b>必备</b>且永不裁剪：
 * <ol>
 *   <li><b>本章蓝图</b> —— 这次要写什么。缺了它模型只能瞎编</li>
 *   <li><b>本章写作要求</b> —— 这次该怎么写（人称、视角、篇幅、禁忌）。缺了它写出来大概率要返工</li>
 * </ol>
 *
 * <p>其余材料（人物、设定、前情摘要、章节原文）走的是和策划同一套取数器 ——
 * 我们全量注入，因为装得下。真正的差异只在"必备"这两块。
 *
 * <h3>为什么正文不写进草稿表</h3>
 * 见 {@code SaveChapterDraftTool} 的类注释：正文本身就是给作者看和改的东西，
 * 加上章节版本机制天然可回滚，再套一层草稿是绕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WriterService {

    /** 保存动作的痕迹 —— 用来判断这一轮到底有没有产出正文 */
    private static final String SAVE_TOOL = "save_chapter_draft";

    private final AgentLoopService agentLoopService;
    private final AgentContextService agentContextService;
    private final ConversationService conversationService;
    private final AgentProfileService profileService;
    private final ChapterService chapterService;
    private final OutlineService outlineService;
    private final ChapterIndex chapterIndex;

    public WriterResult write(Long chapterId, String instruction) {
        Chapter chapter = chapterService.requireById(chapterId);
        Long projectId = chapter.getProjectId();

        // 1. 写作会话（一次任务一段，便于回看"这一章是怎么写出来的"）
        AgentConversation conversation = conversationService.create(projectId, AgentRole.WRITER,
                "写「" + (StringUtils.hasText(chapter.getTitle()) ? chapter.getTitle() : "未命名章") + "」");

        // 2. 必备材料：本章蓝图 + 本章写作要求（REQUIRED，永不裁）
        List<AgentContextSection> required = new ArrayList<>();
        AgentContextSection blueprint = blueprintSection(chapter);
        if (blueprint != null) {
            required.add(blueprint);
        }
        AgentContextSection requirement = writingRequirementSection(chapter);
        if (requirement != null) {
            required.add(requirement);
        }

        // 3. 组装（全量材料 + 必备材料）
        AgentContextAssembly materials = agentContextService.assemble(AgentRole.WRITER, projectId, required);
        String systemPrompt = AgentPrompts.writer(materials.text());

        // 4. 本轮指令落库
        String userInput = StringUtils.hasText(instruction) ? instruction : defaultInstruction(chapter, projectId);
        conversationService.append(conversation.getId(), 0, AgentMessage.user(userInput));

        // 5. 跑循环
        AgentContext context = AgentContext.forChapter(projectId, AgentRole.WRITER,
                conversation.getId(), chapterId);
        AgentResult result = agentLoopService.run(context, systemPrompt, userInput, maxSteps());

        // 6. 回答落库
        conversationService.append(conversation.getId(), result.steps(),
                AgentMessage.assistant(result.answer(), null));

        // 7. 判定是否真的写进了正文（工具的副作用在库里，这里重新读一次确认）
        Chapter updated = chapterService.requireById(chapterId);
        boolean saved = StringUtils.hasText(updated.getContent())
                && result.trace().stream().anyMatch(step -> SAVE_TOOL.equals(step.toolName()));

        log.info("写作任务完成（第 {} 章）：{} 步 / {} / 落库={} / {}",
                chapterId, result.steps(), result.status(), saved, result.usage().describe());

        return new WriterResult(conversation.getId(), result.status().name(), result.answer(),
                result.steps(), result.trace(), result.usage(), materials.report().summary(),
                saved, updated.getCurrentVersionNo(),
                updated.getContent() == null ? 0 : updated.getContent().length());
    }

    /**
     * 本章蓝图 —— 从章节关联的蓝图节点取<b>完整</b>摘要，不截断。
     *
     * <p>注意与策划 Agent 的区别：策划看到的蓝图是截断到 200 字的（它只要知道"规划到哪了"），
     * 而写作要执行的正是这段细节，一个字都不能少。
     */
    private AgentContextSection blueprintSection(Chapter chapter) {
        if (chapter.getOutlineNodeId() == null) {
            return null;
        }
        PlotOutline node = outlineService.requireById(chapter.getOutlineNodeId());
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(node.getName())) {
            sb.append("节点：").append(node.getName().trim()).append('\n');
        }
        if (StringUtils.hasText(node.getSummary())) {
            sb.append(node.getSummary().trim());
        }
        if (sb.isEmpty()) {
            return null;
        }
        return AgentContextSection.required("本章蓝图", sb.toString());
    }

    /** 本章写作要求：风格、人称、篇幅、禁忌等。它和蓝图一样是"这次要干什么"的一部分 */
    private AgentContextSection writingRequirementSection(Chapter chapter) {
        if (!StringUtils.hasText(chapter.getWritingRequirement())) {
            return null;
        }
        return AgentContextSection.required("本章写作要求", chapter.getWritingRequirement().trim());
    }

    /**
     * 不给指令时的默认指令。
     *
     * <p>带上"与已有章节篇幅相当"是有意为之：篇幅是最容易被模型忽略、
     * 又最影响成书观感的一项。与其在提示词里写死一个数字，不如按这本书的实际情况算。
     */
    private String defaultInstruction(Chapter chapter, Long projectId) {
        StringBuilder sb = new StringBuilder();
        sb.append("请写「")
                .append(StringUtils.hasText(chapter.getTitle()) ? chapter.getTitle().trim() : "本章")
                .append("」的正文。");

        int averageChars = averageChapterChars(projectId);
        if (averageChars > 0) {
            sb.append("\n参考篇幅：与本书已有章节相当，约 ").append(averageChars).append(" 字。");
        }
        return sb.toString();
    }

    private int averageChapterChars(Long projectId) {
        List<Chapter> chapters = chapterIndex.ordered(projectId).stream()
                .filter(c -> StringUtils.hasText(c.getContent()))
                .toList();
        if (chapters.isEmpty()) {
            return 0;
        }
        int total = chapters.stream().mapToInt(c -> c.getContent().trim().length()).sum();
        return total / chapters.size();
    }

    private int maxSteps() {
        Integer configured = profileService.require(AgentRole.WRITER).getMaxSteps();
        return configured == null ? 6 : configured;
    }

    /** 写作任务结果 */
    public record WriterResult(Long conversationId, String status, String answer, int steps,
                               List<AgentStep> trace, com.mojian.agent.llm.TokenUsage usage,
                               String contextSummary, boolean saved, Integer versionNo, int charCount) {
    }
}
