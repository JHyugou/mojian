package com.mojian.generation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mojian.generation.GenerationConst;
import com.mojian.generation.lock.TaskLock;
import com.mojian.chapter.entity.Chapter;
import com.mojian.chapter.mapper.ChapterMapper;
import com.mojian.chapter.service.ChapterService;
import com.mojian.common.api.ErrorCode;
import com.mojian.common.exception.BusinessException;
import com.mojian.generation.context.ContextSnapshot;
import com.mojian.generation.context.GenerationContextService;
import com.mojian.generation.context.PromptRenderer;
import com.mojian.generation.dto.GenerationTaskRequest;
import com.mojian.generation.entity.GenerationTask;
import com.mojian.generation.llm.LlmClient;
import com.mojian.generation.llm.LlmRequest;
import com.mojian.generation.llm.StreamCallback;
import com.mojian.generation.mapper.GenerationTaskMapper;
import com.mojian.generation.sse.TaskEventBroker;
import com.mojian.generation.vo.GenerationTaskVO;
import com.mojian.version.entity.ChapterVersion;
import com.mojian.version.mapper.ChapterVersionMapper;
import com.mojian.version.service.VersionNumbers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 生成任务状态机（FR-014）：
 *
 *   CREATED → RUNNING → SUCCEEDED（正文类任务在同一事务内落版本）
 *                     → FAILED（可重试）
 *
 * 可靠性规则（SRS 7.3 / FR-016）：
 * - 防重复：创建前查活动任务 + 数据库 active_slot 唯一约束兜底并发；
 * - 正文永不直接覆盖：任何正式内容都通过版本快照写入；
 * - 终态更新带状态条件（仅 RUNNING 可流转到终态），防止过期回调覆盖新状态；
 * - 服务重启时回收僵尸任务（CREATED/RUNNING → FAILED，允许重试）。
 */
@Slf4j
@Service
public class GenerationTaskService implements ApplicationRunner {

    private final GenerationTaskMapper taskMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterVersionMapper versionMapper;
    private final ChapterService chapterService;
    private final GenerationContextService contextService;
    private final PromptRenderer promptRenderer;
    private final LlmClient llmClient;
    private final TaskEventBroker broker;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final TaskLock taskLock;
    private final Executor executor;

    public GenerationTaskService(GenerationTaskMapper taskMapper,
                                 ChapterMapper chapterMapper,
                                 ChapterVersionMapper versionMapper,
                                 ChapterService chapterService,
                                 GenerationContextService contextService,
                                 PromptRenderer promptRenderer,
                                 LlmClient llmClient,
                                 TaskEventBroker broker,
                                 ObjectMapper objectMapper,
                                 TransactionTemplate transactionTemplate,
                                 TaskLock taskLock,
                                 @Qualifier("generationExecutor") Executor executor) {
        this.taskMapper = taskMapper;
        this.chapterMapper = chapterMapper;
        this.versionMapper = versionMapper;
        this.chapterService = chapterService;
        this.contextService = contextService;
        this.promptRenderer = promptRenderer;
        this.llmClient = llmClient;
        this.broker = broker;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.taskLock = taskLock;
        this.executor = executor;
    }

    // ==================== 创建任务 ====================

    /** 剧情方案生成（两阶段流程第一阶段，FR-010） */
    public GenerationTaskVO createPlanTask(Long chapterId, String instruction) {
        return createTask(chapterId, GenerationConst.TYPE_PLOT_PLAN, instruction, null, null);
    }

    /** 正文/续写/改写/扩缩写/章节记忆任务 */
    public GenerationTaskVO createTask(Long chapterId, GenerationTaskRequest request) {
        String type = request.getTaskType();
        if ((GenerationConst.TYPE_REWRITE.equals(type) || GenerationConst.TYPE_EXPAND_SHRINK.equals(type))
                && (request.getSelection() == null || request.getSelection().isBlank())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "改写/扩缩写必须提供选中文本");
        }
        return createTask(chapterId, type, request.getInstruction(), request.getSelection(), request.getPlan());
    }

    private GenerationTaskVO createTask(Long chapterId, String type,
                                        String instruction, String selection, String plan) {
        Chapter chapter = chapterService.requireById(chapterId);

        // 防重复第一道：已有活动任务直接返回（SRS 11：返回已有运行任务）
        GenerationTask active = findActiveTask(chapterId);
        if (active != null) {
            return GenerationTaskVO.from(active, objectMapper);
        }

        // Redis 开启时：setnx 做第一道快速拦截；未开启时 Noop 直接放行，由 DB 唯一约束兜底
        String lockKey = "mojian:task:active:" + chapterId;
        String lockToken = UUID.randomUUID().toString();
        boolean locked = taskLock.tryLock(lockKey, lockToken);
        if (!locked) {
            GenerationTask winner = findActiveTask(chapterId);
            if (winner != null) {
                return GenerationTaskVO.from(winner, objectMapper);
            }
            // 锁竞争但活动任务尚未落库：继续走 insert，数据库唯一约束仍会兜底
        }

        try {
            ContextSnapshot context = contextService.buildContext(chapter, instruction, selection, plan);

            GenerationTask task = new GenerationTask();
            task.setChapterId(chapterId);
            task.setTaskType(type);
            task.setStatus(GenerationConst.STATUS_CREATED);
            task.setInputSnapshot(toJson(inputJson(instruction, selection, plan)));
            task.setContextSnapshot(toJson(context));
            try {
                taskMapper.insert(task);
            } catch (DuplicateKeyException e) {
                // 防重复第二道：并发窗口内被 active_slot 唯一约束拦下
                GenerationTask winner = findActiveTask(chapterId);
                log.info("章节 {} 并发提交生成任务，复用已有任务 {}", chapterId, winner != null ? winner.getId() : null);
                return GenerationTaskVO.from(winner != null ? winner : task, objectMapper);
            }

            executor.execute(() -> runTask(task.getId()));
            // 异步模式下读到 CREATED；同步执行（测试）时已是终态——以数据库为准
            return GenerationTaskVO.from(taskMapper.selectById(task.getId()), objectMapper);
        } finally {
            if (locked) {
                taskLock.unlock(lockKey, lockToken);
            }
        }
    }

    // ==================== 执行任务 ====================

    void runTask(Long taskId) {
        GenerationTask task = taskMapper.selectById(taskId);
        if (task == null || !GenerationConst.STATUS_CREATED.equals(task.getStatus())) {
            return; // 幂等保护：只执行新建任务
        }
        markRunning(task);

        ContextSnapshot context = readSnapshot(task.getContextSnapshot(), ContextSnapshot.class);
        String type = task.getTaskType();
        LlmRequest request = LlmRequest.of(
                promptRenderer.systemPrompt(context, type),
                promptRenderer.userPrompt(context, type),
                type);

        try {
            // 所有任务统一流式：正文/方案/记忆的产出实时可见，推理模型的思维链同步透出
            llmClient.chatStream(request, new StreamCallback() {
                @Override
                public void onDelta(String delta) {
                    broker.publishDelta(taskId, delta);
                }

                @Override
                public void onReasoning(String delta) {
                    broker.publishReasoning(taskId, delta);
                }

                @Override
                public void onComplete(String fullText) {
                    finishTask(taskId, type, fullText);
                }

                @Override
                public void onError(Throwable error) {
                    failTask(taskId, error.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("任务 {} 执行失败: {}", taskId, e.getMessage());
            failTask(taskId, e.getMessage());
        }
    }

    /** 终态落库：正文类任务在同一事务内写章节版本并推进版本指针 */
    void finishTask(Long taskId, String type, String result) {
        Boolean ok = transactionTemplate.execute(txStatus -> {
            // 条件更新：仅 RUNNING 可流转，过期回调不会覆盖新状态
            // 剧情方案与章节记忆不落正文版本（记忆结果由用户核对后保存）
            boolean contentTask = !GenerationConst.TYPE_PLOT_PLAN.equals(type)
                    && !GenerationConst.TYPE_CHAPTER_MEMORY.equals(type);
            if (contentTask) {
                saveContentVersion(taskId, type, result);
            }
            return transitionTo(taskId, GenerationConst.STATUS_SUCCEEDED, result, null);
        });
        if (Boolean.TRUE.equals(ok)) {
            broker.finish(taskId, GenerationConst.STATUS_SUCCEEDED, null);
        } else {
            log.warn("任务 {} 终态流转被拒绝（状态已变更）", taskId);
        }
    }

    void failTask(Long taskId, String errorMessage) {
        String raw = errorMessage == null ? "未知错误" : errorMessage;
        final String trimmed = raw.length() > 1000 ? raw.substring(0, 1000) : raw;
        Boolean ok = transactionTemplate.execute(txStatus ->
                transitionTo(taskId, GenerationConst.STATUS_FAILED, null, trimmed));
        if (Boolean.TRUE.equals(ok)) {
            broker.finish(taskId, GenerationConst.STATUS_FAILED, trimmed);
        }
    }

    /**
     * 正文落库（FR-013/016）：新正文写入新版本快照，章节指针推进，
     * 续写在原文后追加，改写/扩缩写替换选中文本首次出现位置。
     */
    private void saveContentVersion(Long taskId, String type, String generated) {
        GenerationTask task = taskMapper.selectById(taskId);
        Chapter chapter = chapterMapper.selectById(task.getChapterId());
        String selection = readSnapshot(task.getInputSnapshot(), JsonNode.class) == null
                ? null : readSnapshot(task.getInputSnapshot(), JsonNode.class).path("selection").asText(null);

        String newContent = switch (type) {
            case GenerationConst.TYPE_CONTENT -> generated;
            case GenerationConst.TYPE_CONTINUE -> appendLine(chapter.getContent(), generated);
            default -> replaceSelection(chapter.getContent(), selection, generated);
        };

        int versionNo = VersionNumbers.next(versionMapper, chapter.getId(), chapter.getCurrentVersionNo());
        ChapterVersion version = new ChapterVersion();
        version.setChapterId(chapter.getId());
        version.setVersionNo(versionNo);
        version.setContent(newContent);
        version.setSourceTaskId(taskId);
        version.setSource("AI");
        version.setRemark(taskTypeLabel(type));
        versionMapper.insert(version);

        chapter.setContent(newContent);
        chapter.setCurrentVersionNo(versionNo);
        if ("DRAFT".equals(chapter.getStatus())) {
            chapter.setStatus("WRITING");
        }
        chapterMapper.updateById(chapter);
    }

    /** 状态条件流转：仅当当前为 RUNNING 时更新，返回是否成功 */
    private boolean transitionTo(Long taskId, String status, String result, String error) {
        var update = new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<GenerationTask>()
                .eq(GenerationTask::getId, taskId)
                .eq(GenerationTask::getStatus, GenerationConst.STATUS_RUNNING)
                .set(GenerationTask::getStatus, status)
                .set(GenerationTask::getFinishedAt, LocalDateTime.now());
        if (result != null) {
            update.set(GenerationTask::getResultSnapshot, result);
        }
        if (error != null) {
            update.set(GenerationTask::getErrorMessage, error);
        }
        return taskMapper.update(null, update) > 0;
    }

    private void markRunning(GenerationTask task) {
        task.setStatus(GenerationConst.STATUS_RUNNING);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);
        broker.publishStatus(task.getId(), GenerationConst.STATUS_RUNNING);
    }

    // ==================== 查询 / 重试 / 回收 ====================

    public GenerationTaskVO getTask(Long taskId) {
        return GenerationTaskVO.from(requireById(taskId), objectMapper);
    }

    /** 失败任务重试（FR-014）：复位为 CREATED 后重新执行，不重复建版本 */
    public GenerationTaskVO retry(Long taskId) {
        GenerationTask task = requireById(taskId);
        if (!GenerationConst.STATUS_FAILED.equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只有失败的任务可以重试");
        }
        task.setStatus(GenerationConst.STATUS_CREATED);
        task.setErrorMessage(null);
        task.setStartedAt(null);
        task.setFinishedAt(null);
        task.setResultSnapshot(null);
        try {
            taskMapper.updateById(task);
        } catch (DuplicateKeyException e) {
            throw new BusinessException(ErrorCode.CONFLICT, "该章节已有进行中的任务，请等待完成");
        }
        executor.execute(() -> runTask(taskId));
        return GenerationTaskVO.from(taskMapper.selectById(taskId), objectMapper);
    }

    /** 服务启动时回收僵尸任务（FR-014：重启后长时间 RUNNING 的任务标记失败） */
    @Override
    public void run(ApplicationArguments args) {
        recoverStaleTasks();
    }

    public int recoverStaleTasks() {
        List<GenerationTask> stale = taskMapper.selectList(
                new LambdaQueryWrapper<GenerationTask>()
                        .in(GenerationTask::getStatus, GenerationConst.STATUS_CREATED, GenerationConst.STATUS_RUNNING));
        for (GenerationTask task : stale) {
            task.setStatus(GenerationConst.STATUS_FAILED);
            task.setErrorMessage("服务重启导致任务中断，请重试");
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }
        if (!stale.isEmpty()) {
            log.warn("启动回收僵尸任务 {} 个", stale.size());
        }
        return stale.size();
    }

    public GenerationTask requireById(Long taskId) {
        GenerationTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "生成任务不存在");
        }
        return task;
    }

    private GenerationTask findActiveTask(Long chapterId) {
        return taskMapper.selectOne(new LambdaQueryWrapper<GenerationTask>()
                .eq(GenerationTask::getChapterId, chapterId)
                .in(GenerationTask::getStatus, GenerationConst.STATUS_CREATED, GenerationConst.STATUS_RUNNING)
                .orderByDesc(GenerationTask::getCreatedAt)
                .last("LIMIT 1"));
    }

    // ==================== 工具 ====================

    private String appendLine(String current, String generated) {
        return (current == null || current.isBlank()) ? generated : current + "\n" + generated;
    }

    private String replaceSelection(String content, String selection, String generated) {
        if (content == null || content.isBlank()) {
            return generated;
        }
        if (selection != null && content.contains(selection)) {
            return content.replaceFirst(java.util.regex.Pattern.quote(selection),
                    java.util.regex.Matcher.quoteReplacement(generated));
        }
        // 选中文本已不在正文中（正文被编辑过）：退化为追加，避免误替换
        return content + "\n" + generated;
    }

    private String taskTypeLabel(String type) {
        return switch (type) {
            case GenerationConst.TYPE_CONTENT -> "整章生成";
            case GenerationConst.TYPE_CONTINUE -> "续写";
            case GenerationConst.TYPE_REWRITE -> "选中文本改写";
            case GenerationConst.TYPE_EXPAND_SHRINK -> "选中文本扩缩";
            default -> type;
        };
    }

    private <T> T readSnapshot(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            log.warn("快照解析失败: {}", e.getMessage());
            return null;
        }
    }

    private ObjectNode inputJson(String instruction, String selection, String plan) {
        ObjectNode node = objectMapper.createObjectNode();
        if (instruction != null) node.put("instruction", instruction);
        if (selection != null) node.put("selection", selection);
        if (plan != null) node.put("plan", plan);
        return node;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
