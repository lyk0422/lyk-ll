package com.example.starter.legality.service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 按键分条的进程内锁。嵌入式 H2 内存库仅存在于单个 JVM 生命周期内，
 * 用它串行化同一机组成员的延误延长累计等需要读-判-写一致的短临界区。
 */
final class KeyLock {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** 获取指定键的锁（使用方必须在 finally 中配对 {@link #unlock(String)}）。 */
    void lock(String key) {
        locks.computeIfAbsent(key, k -> new ReentrantLock()).lock();
    }

    /** 释放指定键的锁。 */
    void unlock(String key) {
        ReentrantLock lock = locks.get(key);
        if (lock != null) {
            lock.unlock();
        }
    }
}
