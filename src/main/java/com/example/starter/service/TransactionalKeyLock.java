package com.example.starter.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务期键锁：基于 {@link DetectionLock} 分条互斥，锁一直持有到当前
 * 数据库事务提交或回滚之后才释放。
 *
 * <p>在 afterCompletion 释放而非业务方法返回时释放，是为了堵住
 * “事务尚未提交、其他线程已读到旧版本”的窗口；业务异常导致回滚时
 * 同样会释放，不会死锁。</p>
 */
@Component
public class TransactionalKeyLock {

    private final DetectionLock locks;

    public TransactionalKeyLock(DetectionLock locks) {
        this.locks = locks;
    }

    /**
     * 获取指定键的可重入锁；当前线程必须处于活动事务中，锁在事务完成
     * （提交或回滚）后自动释放。
     */
    public void lockUntilTransactionComplete(String key) {
        locks.lock(key);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    locks.unlock(key);
                }
            });
        } else {
            // 防御：写方法均在事务内调用；无事务上下文时立即释放避免泄漏。
            locks.unlock(key);
        }
    }
}
