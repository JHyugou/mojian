package com.mojian.agent.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.mojian.agent.AgentContext;
import com.mojian.agent.AgentLoopService;
import com.mojian.agent.AgentPrompts;
import com.mojian.agent.AgentResult;
import com.mojian.agent.AgentRole;
import com.mojian.agent.AgentRunListener;
import com.mojian.agent.AgentStep;
import com.mojian.agent.context.AgentContextAssembly;
import com.mojian.agent.context.AgentContextSection;
import com.mojian.agent.entity.AgentConversation;
import com.mojian.agent.llm.AgentMessage;
import com.mojian.agent.llm.TokenUsage;
import com.mojian.agent.material.ChapterIndex;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.service.ChapterService;
import com.mojian.generation.sse.TaskEventBroker;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * 写作 Agent：把某一章的蓝图写成正文。
 *
 * <h3>和策划 Agent 的最大区别：必备材料</h3>
 * 策划的材料全是"可选"（装不下才裁）；写作有两块<b>必备</b>且永不裁剪：
 * <ol>
 *   <li><b>本章蓝图</b> —— 这次要写什么。缺了它模型只能瞎编</li>
 *   <li><b>本章写作要求</b> —— 这次该怎么写（人称、视角、篇幅、禁忌）。缺了它大概率要返工</li>
 * </ol>
 * 其余材料（人物、设定、前情摘要、章节原文）走的是和策划同一套取数器 —— 我们全量注入，因为装得下。
 *
 * <h3>为什么是异步任务</h3>
 * 写一章要两三分钟。同步接口会让前端干等，而且中途刷新页面就断了。
 * 改成"启动任务拿到 taskId → 订阅 SSE"之后：正文<b>逐字推给界面</b>，
 * 刷新页面重连还能拿到已经写好的部分（{@link TaskEventBroker} 会重放缓冲）。
 *
 * <h3>为什么正文不写进草稿表</h3>
 * 见 {@code SaveChapterDraftTool} 的类注释：正文本身就是给作者看和改的东西，
 * 加上章节版本机制天然可回滚，再套一层草稿是绕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WriterService {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    /** 保存动作的痕迹 —— 用来判断这一轮到底有没有产出正文 */
    private static final String SAVE_TOOL = "save_chapter_draft";

    private final AgentLoopService agentLoopService;
    private final AgentContextService agentContextService;
    private final ConversationService conversationService;
    private final AgentProfileService profileService;
    private final ChapterService chapterService;
    private final OutlineService outlineService;
    private final ChapterIndex chapterIndex;
    private final TaskEventBroker broker;
    private final ExecutorService agentTaskExecutor;

    // ==================== 异步任务（给界面用） ====================

    /**
     * 启动一次写作任务，立刻返回任务 id。
     *
     * <p><b>任务 id 与会话 id 现在是两个东西</b>：会话是长期存续的（整本书一个，跨章保持上下文），
     * 而任务是一次性的。之前拿会话 id 当任务 id，是因为「一次任务=一段会话」；
     * 现在会话不再新建，两者必须分开，否则两次写作会共用同一个事件通道。
     */
    public Long startTask(Long chapterId, String instruction) {
        chapterService.requireById(chapterId);
        Long taskId = IdWorker.getId();
        broker.publishStatus(taskId, STATUS_RUNNING);
        agentTaskExecutor.submit(() -> runTask(taskId, chapterId, instruction));
        log.info("写作任务 {} 已提交（第 {} 章）", taskId, chapterId);
        return taskId;
    }

    private void runTask(Long taskId, Long chapterId, String instruction) {
        try {
            WriterResult result = execute(chapterId, instruction, taskListener(taskId));
            broker.publishEvent(taskId, "result",
                    (result.saved() ? "已写入正文 " + result.charCount() + " 字，版本 "
                            + result.versionNo() : "未产出正文") + "｜" + result.answer());
            broker.finish(taskId, STATUS_SUCCEEDED, null);
        } catch (Exception e) {
            log.warn("写作任务 {} 失败: {}", taskId, e.getMessage());
            recordFailure(chapterId, e.getMessage());
            broker.finish(taskId, STATUS_FAILED, e.getMessage());
        }
    }

    /**
     * 失败也要在会话里留一条回复。
     *
     * <p>用户消息是在任务开始前就落库的，失败如果不补一条回答，
     * 会话里就会出现"用户说了话、没人应"的孤立消息。它下次会被当作历史带上，
     * 模型读到一个没有下文的问题，只会莫名困惑。
     *
     * <p>而且从交互上说，对话式界面里"没有回复"比"回复说失败了"更糟 ——
     * 后者至少告诉作者发生了什么、下一步怎么办。
     */
    private void recordFailure(Long chapterId, String message) {
        try {
            Long projectId = chapterService.requireById(chapterId).getProjectId();
            AgentConversation conversation = conversationService.findOrCreateWriter(projectId);
            conversationService.append(conversation.getId(), 0, AgentMessage.assistant(
                    "这次没能完成：" + message + "\n\n正文没有被改动，你可以直接再试一次。", null));
        } catch (Exception e) {
            // 记录失败信息时又失败：只能放弃，绝不能让它盖掉真正的失败原因
            log.warn("记录失败信息时出错（已忽略）: {}", e.getMessage());
        }
    }

    /**
     * 把 Agent 的过程事件转成 SSE 事件。
     *
     * <p>{@code delta} 走 broker 的正文缓冲（断线重连能重放已写部分），
     * 其余过程事件只广播不缓冲 —— 丢了无所谓，不该为了"重连看全过程"把内存变成日志。
     */
    private AgentRunListener taskListener(Long taskId) {
        return new AgentRunListener() {
            @Override
            public void onStepStart(int step) {
                broker.publishEvent(taskId, "progress", "第 " + step + " 步");
            }

            @Override
            public void onAssistantDelta(String delta) {
                broker.publishDelta(taskId, delta);
            }

            @Override
            public void onReasoning(String delta) {
                broker.publishReasoning(taskId, delta);
            }

            @Override
            public void onToolCall(String toolName, String arguments) {
                broker.publishEvent(taskId, "tool-call", toolName);
            }

            @Override
            public void onToolResult(String toolName, String result) {
                broker.publishEvent(taskId, "tool-result", toolName);
            }
        };
    }

    // ==================== 同步执行（调试与异步任务共用） ====================

    public WriterResult write(Long chapterId, String instruction) {
        return execute(chapterId, instruction, AgentRunListener.NOOP);
    }

    private WriterResult execute(Long chapterId, String instruction, AgentRunListener listener) {
        Chapter chapter = chapterService.requireById(chapterId);
        Long projectId = chapter.getProjectId();

        // 1. 写作会话：整本书一个，跨章存续。
        //    这样副 Agent 才记得住你上一章说过的偏好与约束，而不是每章从零开始。
        AgentConversation conversation = conversationService.findOrCreateWriter(projectId);
        List<AgentMessage> history = conversationService.loadTextHistory(conversation.getId());

        // 2. 必备材料：本章蓝图 + 本章写作要求（改稿时再加"现有稿"）。全部 REQUIRED，永不裁
        boolean revising = StringUtils.hasText(chapter.getContent());
        List<AgentContextSection> required = new ArrayList<>();
        AgentContextSection blueprint = blueprintSection(chapter);
        if (blueprint != null) {
            required.add(blueprint);
        }
        AgentContextSection requirement = writingRequirementSection(chapter);
        if (requirement != null) {
            required.add(requirement);
        }
        if (revising) {
            // 没有它，"把第三段改紧凑些"就无从下手 —— 模型看不到第三段是什么
            required.add(AgentContextSection.required("本章现有稿", chapter.getContent().trim()));
        }

        // 3. 组装（全量材料 + 必备材料）。排除当前章：它已作为"本章现有稿"单独注入，
        //    再以"前文原文"的名义出现一遍既是重复（一章五千字），也会让模型分不清哪份算数
        AgentContextAssembly materials = agentContextService.assemble(
                AgentRole.WRITER, projectId, required, chapterId);
        String systemPrompt = AgentPrompts.writer(materials.text(), revising);

        // 4. 本轮指令落库
        String userInput = StringUtils.hasText(instruction) ? instruction : defaultInstruction(chapter, projectId);
        conversationService.append(conversation.getId(), 0, AgentMessage.user(userInput));

        // 5. 跑循环（带上这段会话的历史 —— 这是"跨章保持上下文"落地的地方）
        AgentContext context = AgentContext.forChapter(projectId, AgentRole.WRITER,
                conversation.getId(), chapterId);
        AgentResult result = agentLoopService.run(context, systemPrompt, history, userInput, maxSteps(), listener);

        // 6. 回答落库（进这段长期会话，下一章写作时它会作为历史被带上）
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

    // ==================== 材料 ====================

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
        return sb.isEmpty() ? null : AgentContextSection.required("本章蓝图", sb.toString());
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
     * <p>带上"与已有章节篇幅相当"是有意为之：篇幅是最容易被模型忽略、又最影响成书观感的一项。
     * 与其在提示词里写死一个数字，不如按这本书的实际情况算。
     */
    private String defaultInstruction(Chapter chapter, Long projectId) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(chapter.getContent())) {
            // 改稿但用户没写具体意见：给一个温和的默认动作，而不是让它自由发挥
            sb.append("这是「").append(titleOf(chapter))
                    .append("」的现有稿。请通读一遍，在不改动剧情与关键节点的前提下润色文字：")
                    .append("该紧的地方收紧，该有画面的地方写实，去掉空泛的形容。");
            return sb.toString();
        }

        sb.append("请写「").append(titleOf(chapter)).append("」的正文。");
        int averageChars = averageChapterChars(projectId);
        if (averageChars > 0) {
            sb.append("\n参考篇幅：与本书已有章节相当，约 ").append(averageChars).append(" 字。");
        }
        return sb.toString();
    }

    private String titleOf(Chapter chapter) {
        return StringUtils.hasText(chapter.getTitle()) ? chapter.getTitle().trim() : "本章";
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
                               List<AgentStep> trace, TokenUsage usage,
                               String contextSummary, boolean saved, Integer versionNo, int charCount) {
    }
}
