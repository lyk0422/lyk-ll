package com.example.starter.service;

import java.time.Clock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.error.BusinessException;
import com.example.starter.repository.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等执行器：按 (requestId, action) 幂等。
 * 同键同参返回首次结果快照；同键异参抛出 409 且不执行任何业务写入。
 * 业务写入与幂等记录在同一事务内提交；JVM 内按键分段加锁，使并发同键请求串行化。
 */
@Service
public class IdempotencyService {

    private static final int STRIPES = 64;

    private final IdempotencyRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ReentrantLock[] locks = new ReentrantLock[STRIPES];

    public IdempotencyService(IdempotencyRepository repository,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new ReentrantLock();
        }
    }

    /**
     * 在幂等保护下执行业务动作。
     *
     * @param action      业务动作名
     * @param requestId   幂等请求标识
     * @param fingerprint 全部业务字段指纹
     * @param type        响应类型
     * @param work        业务动作（在事务内执行）
     */
    public <T> T run(String action, String requestId, String fingerprint, Class<T> type,
            Supplier<T> work) {
        ReentrantLock lock = locks[Math.floorMod((action + requestId).hashCode(), STRIPES)];
        lock.lock();
        try {
            var existing = repository.find(requestId, action);
            if (existing.isPresent()) {
                return replay(existing.get(), fingerprint, type);
            }
            try {
                return transactionTemplate.execute(status -> {
                    T result = work.get();
                    repository.insert(requestId, action, fingerprint, writeJson(result), clock.instant());
                    return result;
                });
            } catch (DuplicateKeyException e) {
                // 并发的其他实例/线程已写入同一幂等键：回滚后返回首次结果
                var row = repository.find(requestId, action);
                if (row.isPresent()) {
                    return replay(row.get(), fingerprint, type);
                }
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private <T> T replay(IdempotencyRepository.IdempotencyRow row, String fingerprint, Class<T> type) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BusinessException.conflict(
                    "requestId 已使用且业务参数不一致，拒绝重复处理: " + row.requestId());
        }
        return readJson(row.responseBody(), type);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("无法序列化幂等响应", e);
        }
    }

    private <T> T readJson(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("无法反序列化幂等响应", e);
        }
    }
}
