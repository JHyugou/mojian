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
    private final Map<Long, String> statusByTask = new ConcurrentHashMap<>();

    public synchronized SseEmitter subscribe(Long taskId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        List<SseEmitter> list = emittersByTask.computeIfAbsent(taskId, k -> new CopyOnWriteArrayList<>());
        list.add(emitter);
        Runnable cleanup = () -> list.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());

        // 立即回放：当前状态 + 已缓冲片段，断线重连不丢内容
        String status = statusByTask.getOrDefault(taskId, "CREATED");
        send(emitter, taskId, "status", status);
        String buffered = deltaBufferByTask.getOrDefault(taskId, new StringBuilder()).toString();
        if (!buffered.isEmpty()) {
            send(emitter, taskId, "delta", buffered);
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

    /** 任务终结（成功/失败）后调用：通知并清理正文缓冲，保留最终状态供晚到订阅者 */
    public void finish(Long taskId, String finalStatus, String errorMessage) {
        synchronized (this) {
            statusByTask.put(taskId, finalStatus);
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
        }
    }

    private boolean isFinalStatus(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    /** 心跳：15 秒一帧注释，保持连接、探测死连接 */
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
