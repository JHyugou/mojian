package com.mojian.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 正在跑的对话运行登记表 —— 用来支持"用户中途叫停"。
 *
 * <h3>为什么需要它</h3>
 * 策划对话走的是<b>同步请求</b>：前端点"停止"只能断开 HTTP 连接，
 * 而服务端那边的线程照跑不误 —— 模型还在生成、钱还在花。
 * 用户按下"停下"的意思是"现在停"，不是"我看不见了"。
 *
 * <h3>为什么用 runId 而不是会话 id</h3>
 * 新开一段对话时，会话 id 要等后端返回才知道，前端根本无法用它来叫停。
 * 所以由前端生成一个 requestId 随请求发上来，叫停时再拿着它找回来。
 *
 * <h3>为什么不是线程中断</h3>
 * 在写作任务那边试过 {@code Future.cancel(true)} 那套：HTTP 客户端的读操作
 * 不一定响应中断，中断标记还可能被中途某个"捕获了 InterruptedException
 * 但没恢复标记"的调用清掉。实测结论是<b>不可靠</b>。
 * 一个自己维护的标记，行为是可预期的。
 */
@Slf4j
@Service
public class AgentRunRegistry {

    /** 已被要求停止的运行 */
    private final Set<String> cancelled = ConcurrentHashMap.newKeySet();

    /** 正在跑的运行（用于区分"叫停了一个早就结束的"） */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public void begin(String runId) {
        if (runId != null && !runId.isBlank()) {
            running.add(runId);
            log.debug("运行 {} 开始登记（当前在跑 {} 个）", runId, running.size());
            // 同一个 id 复用（比如前端重试）时，把上一轮的取消标记清掉
            cancelled.remove(runId);
        }
    }

    /** 用户点了停止 */
    public boolean cancel(String runId) {
        if (runId == null || runId.isBlank()) {
            return false;
        }
        boolean wasRunning = running.contains(runId);
        cancelled.add(runId);
        log.info("运行 {} 已收到停止请求（{}）", runId, wasRunning ? "正在跑" : "已结束或未开始");
        return wasRunning;
    }

    public boolean isCancelled(String runId) {
        return runId != null && cancelled.contains(runId);
    }

    public void end(String runId) {
        if (runId != null && !runId.isBlank()) {
            running.remove(runId);
            cancelled.remove(runId);
        }
    }
}
