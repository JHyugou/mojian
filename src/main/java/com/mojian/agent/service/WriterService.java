package com.mojian.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.entity.GenerationTask;
import com.mojian.generation.mapper.GenerationTaskMapper;
import com.mojian.generation.sse.TaskEventBroker;
import com.mojian.outline.entity.PlotOutline;
import com.mojian.outline.service.OutlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

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
    public static final String STATUS_CANCELLED = "CANCELLED";

    /**
     * 正在跑的任务，用来支持"终止"。
     *
     * <p>光让前端断开 SSE 是不够的 —— 后端的线程还在跑、模型还在生成、钱还在花。
     * 用户按下终止的意思是"现在停"，不是"我看不见了"。
     */
    private final Map<Long, Future<?>> runningTasks = new ConcurrentHashMap<>();
    /** 被主动终止的任务 id。用来把"用户停的"和"自己失败的"区分开 */
    private final Set<Long> cancelledTasks = ConcurrentHashMap.newKeySet();

    private final AgentLoopService agentLoopService;
    private final AgentContextService agentContextService;
    private final ConversationService conversationService;
    private final AgentProfileService profileService;
    private final ChapterService chapterService;
    private final OutlineService outlineService;
    private final ChapterIndex chapterIndex;
    private final TaskEventBroker broker;
    private final AgentUsageService usageService;
    private final ChapterMemoryWorker memoryWorker;
    private final GenerationTaskMapper generationTaskMapper;
    private final AgentRunService runService;
    private final ExecutorService agentTaskExecutor;

    /** 写作任务的统一类型标记（写进 generation_task.task_type） */
    public static final String TASK_TYPE_AGENT_WRITE = "AGENT_WRITE";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ==================== 异步任务（给界面用） ====================

    /**
     * 启动一次写作任务，立刻返回任务 id。
     *
     * <p>任务会先落一条 {@code generation_task}（task_type=AGENT_WRITE），
     * 再提交线程池。这一步是可靠性的地基：
     * <ul>
     *   <li><b>防重复</b>：表的 {@code (chapter_id, active_slot)} 唯一索引在数据库层保证
     *       同一章同时只有一个活动任务 —— 之前内存 Map 拦不住并发/多实例</li>
     *   <li><b>可审计</b>：谁、什么时候、跑成没跑成，都有一行记录</li>
     *   <li><b>可恢复</b>：服务重启后，启动期的僵尸任务回收会把 RUNNING 标成 FAILED，
     *       而不是让界面永远停在"写作中"</li>
     * </ul>
     */
    public Long startTask(Long chapterId, String instruction) {
        chapterService.requireById(chapterId);

        GenerationTask task = new GenerationTask();
        task.setChapterId(chapterId);
        task.setTaskType(TASK_TYPE_AGENT_WRITE);
        task.setStatus(STATUS_RUNNING);
        task.setStartedAt(LocalDateTime.now());
        task.setInputSnapshot(writeInputSnapshot(instruction));
        try {
            generationTaskMapper.insert(task);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "这一章已经有一个写作任务在跑。等它结束，或先把它终止再重试。");
        }

        Long taskId = task.getId();
        broker.publishStatus(taskId, STATUS_RUNNING);
        Future<?> future;
        try {
            future = agentTaskExecutor.submit(() -> runTask(taskId, chapterId, instruction));
        } catch (RuntimeException e) {
            // 线程池拒绝（队列满/正在关闭）：不能让任务行永远停在 RUNNING
            updateTaskStatus(taskId, STATUS_FAILED, null, "写作任务执行器繁忙，请稍后重试");
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "写作任务执行器繁忙，请稍后重试");
        }
        runningTasks.put(taskId, future);
        log.info("写作任务 {} 已提交（第 {} 章）", taskId, chapterId);
        return taskId;
    }

    /**
     * 终止一个正在跑（或还在排队）的写作任务。
     *
     * <p>{@code cancel(true)} 会中断线程 —— 循环在下一轮开始前会看到中断标记并停下，
     * 正在等模型返回的 HTTP 调用也会被中断。两者都必要：只标记不中断的话，
     * 得等当前这次模型调用跑完（可能一两分钟）才生效，那期间照样计费。
     *
     * @return 是否真的终止了一个在跑的任务
     */
    public boolean cancel(Long taskId) {
        Future<?> future = runningTasks.get(taskId);
        // 即使线程池里已经没有（比如排队时被取消），也要把数据库里的活动状态收掉
        updateTaskStatus(taskId, STATUS_CANCELLED, null, null);
        if (future == null) {
            return false;
        }
        cancelledTasks.add(taskId);
        future.cancel(true);
        broker.finish(taskId, STATUS_CANCELLED, null);
        log.info("写作任务 {} 已被用户终止", taskId);
        return true;
    }

    /** 任务是否还在跑 */
    public boolean isRunning(Long taskId) {
        return runningTasks.containsKey(taskId);
    }

    private void runTask(Long taskId, Long chapterId, String instruction) {
        try {
            WriterResult result = execute(chapterId, instruction, taskListener(taskId));
            if (result.status().equals(AgentResult.Status.CANCELLED.name())) {
                // 用户停的：如实说"停了"，不要伪装成"写完了"
                updateTaskStatus(taskId, STATUS_CANCELLED, null, null);
                broker.publishEvent(taskId, "result", "已按你的要求停下");
                broker.finish(taskId, STATUS_CANCELLED, null);
                return;
            }
            String summary = (result.saved()
                    ? "已写入正文 " + result.charCount() + " 字，版本 " + result.versionNo()
                    : "未产出正文") + "｜" + (result.answer() == null ? "" : result.answer());
            updateTaskStatus(taskId, STATUS_SUCCEEDED, summary, null);
            broker.publishEvent(taskId, "result", summary);
            broker.finish(taskId, STATUS_SUCCEEDED, null);
        } catch (Exception e) {
            // 区分"用户停的"和"自己失败的"：前者不是错误，日志里不该出现 WARN
            if (cancelledTasks.contains(taskId) || Thread.currentThread().isInterrupted()) {
                log.info("写作任务 {} 已终止", taskId);
                updateTaskStatus(taskId, STATUS_CANCELLED, null, null);
                broker.finish(taskId, STATUS_CANCELLED, null);
                return;
            }
            log.warn("写作任务 {} 失败: {}", taskId, e.getMessage());
            updateTaskStatus(taskId, STATUS_FAILED, null, e.getMessage());
            recordFailure(chapterId, e.getMessage());
            broker.finish(taskId, STATUS_FAILED, e.getMessage());
        } finally {
            runningTasks.remove(taskId);
            cancelledTasks.remove(taskId);
        }
    }

    /** 条件更新任务终态：只有 CREATED/RUNNING 才能流转，避免重复回调覆盖 */
    private void updateTaskStatus(Long taskId, String status, String result, String error) {
        try {
            var update = Wrappers.<GenerationTask>lambdaUpdate()
                    .eq(GenerationTask::getId, taskId)
                    .in(GenerationTask::getStatus, "CREATED", STATUS_RUNNING)
                    .set(GenerationTask::getStatus, status)
                    .set(GenerationTask::getFinishedAt, LocalDateTime.now());
            if (result != null) {
                update.set(GenerationTask::getResultSnapshot, result);
            }
            if (error != null) {
                update.set(GenerationTask::getErrorMessage,
                        error.length() > 1000 ? error.substring(0, 1000) : error);
            }
            generationTaskMapper.update(null, update);
        } catch (Exception e) {
            // 状态写失败不该影响任务本身：正文已经落库，这才是最重要的
            log.warn("写作任务 {} 状态写回失败（不影响正文）: {}", taskId, e.getMessage());
        }
    }

    private String writeInputSnapshot(String instruction) {
        try {
            return MAPPER.writeValueAsString(Map.of("instruction", instruction == null ? "" : instruction));
        } catch (Exception e) {
            return "{}";
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
            /**
             * 终止标记检查。循环每轮、以及每次动手执行工具之前都会问它。
             *
             * <p>不依赖线程中断：实测 {@code Future.cancel(true)} 不保险 ——
             * 结果是用户点了终止，任务照样跑完、照样把正文写进书里。
             * 一个自己维护的标记，行为是可预期的。
             */
            @Override
            public boolean cancelled() {
                return cancelledTasks.contains(taskId);
            }

            @Override
            public void onStepStart(int step) {
                broker.publishEvent(taskId, "progress", "第 " + step + " 步");
            }

            @Override
            public void onAssistantDelta(String delta) {
                broker.publishDelta(taskId, delta);
            }

            @Override
            public void onToolContentDelta(String toolName, String delta) {
                // 正文通过工具参数传，这才是"边写边看"真正的内容来源。
                // 走 content 通道而不是 delta：delta 是"它说了什么"（回答），
                // 混在一起的话回答会被拼到正文后面，界面上就分不清了
                broker.publishContent(taskId, delta);
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

    /**
     * 预览一次写作会装进上下文的材料（只组装，不调模型）。
     *
     * <p>为什么把它抽出来给 context-report 用：之前右栏写作 Agent 的"上下文占用"
     * 是拿 projectId 空组装出来的 —— 既没有本章蓝图/写作要求，也没有排除未来章节，
     * 数值看着"真实"其实不是实际发出去的那份。现在两处共用同一个组装方法，
     * 界面上的百分比才真的能解释"模型看到了什么"。
     */
    public AgentContextAssembly contextPreview(Long chapterId) {
        Chapter chapter = chapterService.requireById(chapterId);
        return assembleMaterials(chapter, StringUtils.hasText(chapter.getContent()));
    }

    /**
     * 组装写作上下文：全量材料 + 本章必备材料。
     *
     * <p>排除当前章：它已作为"本章现有稿"单独注入，再以"前文原文"的名义出现一遍
     * 既是重复（一章五千字），也会让模型分不清哪份算数。
     * 同时排除本章蓝图节点，避免"已有蓝图"里出现第二份任务书。
     */
    private AgentContextAssembly assembleMaterials(Chapter chapter, boolean revising) {
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
        return agentContextService.assemble(AgentRole.WRITER, chapter.getProjectId(), required,
                chapter.getId(), chapter.getOutlineNodeId());
    }

    private WriterResult execute(Long chapterId, String instruction, AgentRunListener listener) {
        Chapter chapter = chapterService.requireById(chapterId);
        Long projectId = chapter.getProjectId();
        // 保存成功的判定基准：版本号只在真正写入正文时递增。
        // 之前用"trace 里出现过 save_chapter_draft"判断，工具失败也会记 trace → 假阳性。
        int versionBefore = chapter.getCurrentVersionNo() == null ? 0 : chapter.getCurrentVersionNo();

        // 1. 写作会话：整本书一个，跨章存续。
        //    这样副 Agent 才记得住你上一章说过的偏好与约束，而不是每章从零开始。
        AgentConversation conversation = conversationService.findOrCreateWriter(projectId);
        List<AgentMessage> history = ConversationService.trimHistory(
                conversationService.loadTextHistory(conversation.getId()), 80_000);

        // 2. 必备材料：本章蓝图 + 本章写作要求（改稿时再加"现有稿"）。全部 REQUIRED，永不裁
        boolean revising = StringUtils.hasText(chapter.getContent());
        // 3. 组装（全量材料 + 必备材料；排除当前章与本章蓝图节点）
        AgentContextAssembly materials = assembleMaterials(chapter, revising);
        String systemPrompt = AgentPrompts.writer(materials.text(), revising);
        // 4. 本轮指令落库
        String userInput = StringUtils.hasText(instruction) ? instruction : defaultInstruction(chapter, projectId);

        /*
         * 历史与材料共享同一个上下文窗口。
         * 材料预算只覆盖材料，历史之前是固定 8 万字符；在 128K 模型上
         * "材料 10 万 + 历史 8 万" 会直接超窗。这里按剩余空间再裁一次。
         */
        com.mojian.agent.context.AgentContextBudget budget =
                agentContextService.budgetOf(AgentRole.WRITER);
        int historyBudget = Math.max(12_000,
                budget.usableTokens() - materials.report().usedTokens() - userInput.length() - 6_000);
        history = ConversationService.trimHistory(history, historyBudget);

        conversationService.append(conversation.getId(), 0, AgentMessage.user(userInput));

        // 5. 跑循环（带上这段会话的历史 —— 这是"跨章保持上下文"落地的地方）
        AgentContext context = AgentContext.forChapter(projectId, AgentRole.WRITER,
                conversation.getId(), chapterId);
        long loopStartedAt = System.currentTimeMillis();
        AgentResult result;
        try {
            result = agentLoopService.run(context, systemPrompt, history, userInput, maxSteps(), listener);
        } catch (RuntimeException e) {
            runService.recordFailure(context, System.currentTimeMillis() - loopStartedAt, e.getMessage());
            throw e;
        }

        // 6. 回答落库（进这段长期会话，下一章写作时它会作为历史被带上）
        conversationService.append(conversation.getId(), result.steps(),
                AgentMessage.assistant(result.answer(), null));

        // 7. 判定是否真的写进了正文：以版本号是否前进为准（工具的副作用在库里）
        Chapter updated = chapterService.requireById(chapterId);
        int versionAfter = updated.getCurrentVersionNo() == null ? 0 : updated.getCurrentVersionNo();
        boolean saved = versionAfter > versionBefore;

        // 8. 记账。被终止的运行也要记：钱已经花出去了，不能因为"没写完"就不算
        usageService.record(context, result.usage());
        runService.record(context, result, System.currentTimeMillis() - loopStartedAt,
                materials.report().summary());

        /*
         * 9. 收尾：正文真的写进库后，自动在后台提炼一次章节记忆。
         * 这是"长篇连续性"闭环的一半 —— 之前完全靠作者记得手动点一次。
         * 失败只记日志，不影响写作结果；作者仍可在界面上改。
         */
        if (saved) {
            memoryWorker.schedule(projectId, chapterId);
            log.info("章节 {} 已写入正文，已排入章节记忆自动提炼", chapterId);
        }

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
            /*
             * 没关联蓝图时，不能什么都不给：系统提示词要求"严格按【本章蓝图】写"，
             * 模型找不到这块材料，要么追问、要么自己编一条主线 —— 后者最危险。
             * 这里显式把"没有蓝图"作为 REQUIRED 信息告诉它，把决定权交还作者。
             */
            return AgentContextSection.required("本章蓝图",
                    "（这一章还没有关联蓝图节点。请按作者的写作要求与对话指令来写；"
                            + "如果关键剧情信息不足，先向作者提问，不要自行编造关键设定或主线走向。）");
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
