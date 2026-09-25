package com.example.starter.support;

import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.web.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * requestId 幂等执行模板。调用方必须处于业务事务内：
 * <ol>
 *     <li>先以独立事务插入 PROCESSING 占位抢占 requestId，保证并发同键只有一个请求执行业务；</li>
 *     <li>同键异参（指纹不同）一律 409，即使首个请求仍在处理中；</li>
 *     <li>业务事务提交后以独立事务落 DONE 首次结果；回滚后标记 FAILED 释放占位；</li>
 *     <li>并发的同键同参请求等待首个请求落库后返回首次结果。</li>
 * </ol>
 */
@Component
public class IdempotencySupport {

    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final long WAIT_STEP_MILLIS = 10;

    private final IdempotencyRepository repository;
    private final IdempotencyTx idempotencyTx;
    private final FingerprintHasher hasher;
    private final ObjectMapper objectMapper;

    public IdempotencySupport(IdempotencyRepository repository, IdempotencyTx idempotencyTx,
                              FingerprintHasher hasher, ObjectMapper objectMapper) {
        this.repository = repository;
        this.idempotencyTx = idempotencyTx;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    public <T> T execute(String requestId, String requestKind, Object payload,
                         Class<T> responseType, int successStatus, Supplier<T> action) {
        String fingerprint = hasher.hash(requestId, requestKind, payload);
        String replayedBody = acquire(requestId, requestKind, fingerprint);
        if (replayedBody != null) {
            return deserialize(replayedBody, responseType);
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("幂等执行必须包裹在业务事务中");
        }
        Outcome<T> outcome = new Outcome<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    idempotencyTx.markDone(requestId, successStatus,
                            objectMapper.writeValueAsString(outcome.value));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("幂等结果序列化失败", e);
                }
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    idempotencyTx.markFailed(requestId);
                }
            }
        });
        T result = action.get();
        outcome.value = result;
        return result;
    }

    /**
     * 抢占 requestId：返回 DONE 首次结果表示重放；返回 null 表示本请求抢占成功、负责执行业务。
     * PROCESSING 状态下短暂等待首个请求完成。
     */
    private String acquire(String requestId, String requestKind, String fingerprint) {
        long deadlineNanos = System.nanoTime() + WAIT_TIMEOUT.toNanos();
        while (true) {
            var existing = repository.find(requestId);
            if (existing.isEmpty()) {
                try {
                    idempotencyTx.insertProcessing(requestId, requestKind, fingerprint);
                    return null;
                } catch (DuplicateKeyException e) {
                    continue;
                }
            }
            var record = existing.get();
            if (!record.fingerprint().equals(fingerprint)) {
                throw new ConflictException("requestId 已用于不同的业务请求：" + requestId);
            }
            switch (record.status()) {
                case "DONE" -> {
                    return record.responseBody();
                }
                case "FAILED" -> {
                    // 指纹在首次占位时已与 requestId 永久绑定：异参即使首次失败仍 409，仅同参可重试。
                    if (idempotencyTx.takeOverFailed(requestId)) {
                        return null;
                    }
                }
                default -> {
                    if (System.nanoTime() >= deadlineNanos) {
                        throw new ConflictException("requestId 对应请求仍在处理中，请稍后重试：" + requestId);
                    }
                    try {
                        Thread.sleep(WAIT_STEP_MILLIS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ConflictException("等待幂等请求完成时被中断：" + requestId);
                    }
                }
            }
        }
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等结果反序列化失败", e);
        }
    }

    private static final class Outcome<T> {
        private volatile T value;
    }
}
