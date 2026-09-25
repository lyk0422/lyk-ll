package com.example.starter.idempotent;

import com.example.starter.web.IdempotentConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * 幂等执行器：指纹覆盖 requestId 与全部业务字段。
 *
 * <p>裁决方式：先以 requestId 主键插入“占位记录”（与业务在同一事务），
 * 并发同键请求会在唯一索引上阻塞并拿到 DuplicateKeyException，从而被串行裁决；
 * 业务全部完成后于同事务回写首次结果。同键同参返回首次结果（replay=true）；
 * 同键异参抛 {@link IdempotentConflictException}（409），此路径不执行任何业务写入。
 */
@Service
public class IdempotencyService {

    private static final String PLACEHOLDER = "";

    private final IdempotentRecordRepository repository;
    private final FingerprintUtil fingerprintUtil;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotentRecordRepository repository,
                              FingerprintUtil fingerprintUtil,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.fingerprintUtil = fingerprintUtil;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public <T> ReplayResult<T> run(String requestId, Long stationId, String apiType,
                                   Object payload, int httpStatus, Supplier<T> action,
                                   Class<T> resultType) {
        String fingerprint = fingerprintUtil.fingerprint(requestId, payload);

        IdempotentRecord existing = repository.find(requestId).orElse(null);
        if (existing == null) {
            try {
                repository.insert(new IdempotentRecord(requestId, stationId, apiType,
                        fingerprint, httpStatus, PLACEHOLDER, null));
            } catch (DuplicateKeyException concurrent) {
                // 同键并发请求已先占位，按已落库的首次记录裁决
                existing = waitForCompleted(requestId);
                return resolveExisting(existing, fingerprint, resultType);
            }
            T result = action.get();
            repository.updateResult(requestId, httpStatus, toJson(result));
            return new ReplayResult<>(result, false, httpStatus);
        }
        return resolveExisting(existing, fingerprint, resultType);
    }

    @SuppressWarnings("unchecked")
    private <T> ReplayResult<T> resolveExisting(IdempotentRecord existing, String fingerprint,
                                                Class<T> resultType) {
        if (!existing.fingerprint().equals(fingerprint)) {
            // 同键异参：409，不触发任何业务逻辑、不改变阀门状态
            throw new IdempotentConflictException(existing.requestId());
        }
        T firstResult = (T) fromJson(existing.resultJson(), resultType);
        if (firstResult instanceof Replayable<?> replayable) {
            firstResult = (T) replayable.withReplay(true);
        }
        return new ReplayResult<>(firstResult, true, existing.httpStatus());
    }

    /**
     * 防御性等待：理论上占位与回写同事务提交，重查时必为完成态；此处仅兜底极端可见性场景。
     */
    private IdempotentRecord waitForCompleted(String requestId) {
        for (int i = 0; i < 50; i++) {
            IdempotentRecord record = repository.find(requestId)
                    .orElseThrow(() -> new IdempotentConflictException(requestId));
            if (!PLACEHOLDER.equals(record.resultJson())) {
                return record;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IdempotentConflictException(requestId);
            }
        }
        throw new IdempotentConflictException(requestId);
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize idempotent result", e);
        }
    }

    private Object fromJson(String json, Class<?> resultType) {
        try {
            return objectMapper.readValue(json, resultType);
        } catch (Exception e) {
            throw new IllegalStateException("failed to deserialize idempotent result", e);
        }
    }
}
