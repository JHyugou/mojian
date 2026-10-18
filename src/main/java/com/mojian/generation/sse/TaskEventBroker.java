package com.mojian.generation.sse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 任务事件中枢（FR-015）：内存态的 SSE 广播 + 已生成内容缓冲。
 *
 * - 同一任务允许多个订阅者（刷新页面后重连）；
 * - 新订阅者立即收到当前状态 + 已缓冲的正文片段（断线重连不丢内容）；
 * - 心跳注释帧保持连接活性并探测死连接（FR-013 用户关页感知）；
 * - 任务的权威状态在数据库 generation_task，本类挂掉不影响数据正确性。
 */
@Slf4j
@Service
public class TaskEventBroker {

    private static final long EMITTER_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emittersByTask = new ConcurrentHashMap<>();
    private final Map<Long, StringBuilder> deltaBufferByTask = new ConcurrentHashMap<>();
    private final Map<Long, StringBuilder> contentBufferByTask = new ConcurrentHashMap<>();
    private final Map<Long, StringBuilder> reasoningBufferByTask = new ConcurrentHashMap<>();
    private final Map<Long, String> statusByTask = new ConcurrentHashMap<>();
    /** 终结任务的状态保留到这个时间点（毫秒），之后清掉，避免长跑进程里无限增长 */
    private final Map<Long, Long> statusExpireAt = new ConcurrentHashMap<>();
    private static final long FINAL_STATUS_TTL_MILLIS = 30 * 60 * 1000L;

    public synchronized SseEmitter subscribe(Long taskId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        List<SseEmitter> list = emittersByTask.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> list.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // 立即回放：当前状态 + 已缓冲片段，断线重连不丢内容。
        // 注意事件名带 -snapshot：前端据此"替换"而不是"追加"，
        // 否则重连时同一个片段的广播会再追加一遍（正文会整章重复）。
        String status = statusByTask.getOrDefault(taskId, "CREATED");
        send(emitter, taskId, "status", status);
        String bufferedReasoning = reasoningBufferByTask.getOrDefault(taskId, new StringBuilder()).toString();
        if (!bufferedReasoning.isEmpty()) {
            send(emitter, taskId, "reasoning-snapshot", bufferedReasoning);
        }
        String buffered = deltaBufferByTask.getOrDefault(taskId, new StringBuilder()).toString();
        if (!buffered.isEmpty()) {
            send(emitter, taskId, "delta-snapshot", buffered);
        }
        String bufferedContent = contentBufferByTask.getOrDefault(taskId, new StringBuilder()).toString();
        if (!bufferedContent.isEmpty()) {
            send(emitter, taskId, "content-snapshot", bufferedContent);
        }
        if (isFinalStatus(status)) {
            send(emitter, taskId, "end", status);
            emitter.complete();
        }
        return emitter;
    }

    public void publishStatus(Long taskId, String status) {
        synchronized (this) {
            statusByTask.put(taskId, status);
        }
        broadcast(taskId, "status", status);
    }

    public void publishDelta(Long taskId, String delta) {
        synchronized (this) {
            deltaBufferByTask.computeIfAbsent(taskId, k -> new StringBuilder()).append(delta);
        }
        broadcast(taskId, "delta", delta);
    }

    /** 思维链片段：缓冲供重连重放，实时广播给订阅者 */
    public void publishReasoning(Long taskId, String delta) {
        synchronized (this) {
            reasoningBufferByTask.computeIfAbsent(taskId, k -> new StringBuilder()).append(delta);
        }
        broadcast(taskId, "reasoning", delta);
    }

    /**
     * 正文增量。
     *
     * <p><b>为什么和 {@link #publishDelta} 分开</b>：两者是不同性质的东西 ——
     * delta 是"它说了什么"（对话流里的回答），content 是"它写了什么"（正文）。
     * 混在一个通道里，回答会被拼到正文后面去，界面上就分不清哪是哪了。
     */
    public void publishContent(Long taskId, String delta) {
        synchronized (this) {
            contentBufferByTask.computeIfAbsent(taskId, k -> new StringBuilder()).append(delta);
        }
        broadcast(taskId, "content", delta);
    }

    /**
     * 自定义事件（工具调用、阶段变化、结果摘要等）。
     *
     * <p>只广播、不缓冲：重连时的关键信息已经由 {@code status} 与 {@code delta} 两个缓冲恢复了，
     * 中间的过程事件丢了无所谓 —— 不该为了"重连能看全过程"把内存变成无上限的日志。
     */
    public void publishEvent(Long taskId, String event, String data) {
        broadcast(taskId, event, data);
    }

    /** 任务终结（成功/失败）后调用：通知并清理正文缓冲，保留最终状态供晚到订阅者 */
    public void finish(Long taskId, String finalStatus, String errorMessage) {
        synchronized (this) {
            // 幂等：取消接口与任务线程可能先后各调一次。
            // 重复 finish 会让前端收到两个 end，状态栏闪两下，日志也难对。
            String previous = statusByTask.get(taskId);
            if (isFinalStatus(previous)) {
                return;
            }
            statusByTask.put(taskId, finalStatus);
            statusExpireAt.put(taskId, System.currentTimeMillis() + FINAL_STATUS_TTL_MILLIS);
        }
        broadcast(taskId, "status", finalStatus);
        if (errorMessage != null) {
            broadcast(taskId, "task-error", errorMessage);
        }
        broadcast(taskId, "end", finalStatus);
        for (SseEmitter emitter : emittersByTask.getOrDefault(taskId, new CopyOnWriteArrayList<>())) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端可能已关闭
            }
        }
        synchronized (this) {
            emittersByTask.remove(taskId);
            deltaBufferByTask.remove(taskId);
            contentBufferByTask.remove(taskId);
            reasoningBufferByTask.remove(taskId);
        }
    }

    private boolean isFinalStatus(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    /** 心跳：15 秒一帧注释，保持连接、探测死连接；顺带清掉过期任务状态 */
    @Scheduled(fixedRate = 15000)
    public void heartbeat() {
        emittersByTask.forEach((taskId, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | IllegalStateException e) {
                    list.remove(emitter);
                }
            }
        });
        long now = System.currentTimeMillis();
        statusExpireAt.forEach((taskId, expireAt) -> {
            if (expireAt < now && !emittersByTask.containsKey(taskId)) {
                statusByTask.remove(taskId);
                statusExpireAt.remove(taskId);
            }
        });
    }

    private void broadcast(Long taskId, String event, String data) {
        for (SseEmitter emitter : emittersByTask.getOrDefault(taskId, new CopyOnWriteArrayList<>())) {
            send(emitter, taskId, event, data);
        }
    }

    private void send(SseEmitter emitter, Long taskId, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(String.valueOf(data)));
        } catch (IOException | IllegalStateException e) {
            emittersByTask.getOrDefault(taskId, new CopyOnWriteArrayList<>()).remove(emitter);
        }
    }
}
