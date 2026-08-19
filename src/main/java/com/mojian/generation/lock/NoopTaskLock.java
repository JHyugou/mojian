package com.mojian.generation.lock;

/**
 * Redis 未启用时的空实现：不额外加锁，依赖数据库 active_slot 唯一约束兜底。
 */
public class NoopTaskLock implements TaskLock {

    @Override
    public boolean tryLock(String key, String token) {
        return true;
    }

    @Override
    public void unlock(String key, String token) {
        // no-op
    }
}
