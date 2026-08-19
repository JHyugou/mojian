package com.mojian.generation.lock;

/**
 * 生成任务创建期的分布式锁抽象。
 * Redis 开启时用 setnx 做第一道快速拦截；关闭时使用 Noop 实现，仅靠数据库唯一约束兜底。
 */
public interface TaskLock {

    boolean tryLock(String key, String token);

    void unlock(String key, String token);
}
