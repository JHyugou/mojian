package com.mojian.agent.service;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 章节记忆的后台执行器。
 *
 * <p>写成独立组件而不是往 AgentConfig 里再加一个 ExecutorService Bean：
 * 容器里已经有一个写作任务专用池，再暴露同类型的 Bean 会让"按类型注入"
 * 出现歧义（必须靠名字/Qualifier 才能区分，容易埋坑）。
 * 这里把线程池封装进组件内部，对外只暴露一个 {@code schedule}，职责更清楚。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChapterMemoryWorker {

    private final ChapterMemoryService memoryService;

    /** 单线程足够：收尾任务串行执行，也不急；独立于写作任务池 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "agent-memory");
        thread.setDaemon(true);
        return thread;
    });

    public void schedule(Long projectId, Long chapterId) {
        executor.submit(() -> memoryService.generateQuietly(projectId, chapterId));
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
