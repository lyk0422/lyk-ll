package com.example.starter.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JVM 内按业务键（检测 ID、轨迹 UID、目标类别）分条的可重入锁注册表。
 *
 * <p>仅保证同一 JVM 内互斥（与题干范围一致）；锁的获取/释放时机由
 * {@link TransactionalKeyLock} 绑定到数据库事务，数据库行锁与乐观锁
 * 版本条件仍是最终防线。</p>
 */
@Component
public class DetectionLock {

    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public void lock(String key) {
        locks.computeIfAbsent(key, k -> new ReentrantLock()).lock();
    }

    public void unlock(String key) {
        ReentrantLock lock = locks.get(key);
        if (lock != null) {
            lock.unlock();
        }
    }
}
