package com.example.starter.atm.service;

import com.example.starter.atm.repo.IdempotentRepository;
import com.example.starter.atm.support.DomainClock;
import com.example.starter.atm.web.ApiException;
import com.example.starter.atm.web.ConflictException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.util.Map;
import java.util.function.Supplier;

/**
 * requestId 幂等执行器。
 * 指纹覆盖 endpoint 与全部业务字段（含路径单号）：同键同参返回首次结果，同键异参返回 409。
 * 首次请求先以独立事务插入 PENDING 占位；并发同键请求等待首个请求落库结果后原样回放。
 */
@Component
public class IdempotencyService {

    /** 幂等执行结果：status 为 HTTP 状态码，replayed 标识是否为重放命中。 */
    public record Outcome(int status, Object body, boolean replayed) {
    }

    private static final int PENDING = 0;
    private static final long WAIT_MAX_MILLIS = 10_000L;

    private final IdempotentRepository repository;
    private final DomainClock clock;
    private final ObjectMapper objectMapper;
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    public IdempotencyService(IdempotentRepository repository,
                             DomainClock clock,
                             ObjectMapper objectMapper,
                             PlatformTransactionManager txManager) {
        this.repository = repository;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @param fingerprint 调用方对 endpoint + 全部业务字段计算出的 SHA-256 指纹
     * @param type        成功响应体类型（重放时按该类型还原）
     * @param action      首次执行的业务动作，返回事务内一起提交
     */
    public <T> Outcome execute(String requestId, String endpoint, String fingerprint,
                               Class<T> type, Supplier<T> action) {
        // 独立短事务抢占幂等键
        boolean acquired;
        try {
            acquired = java.lang.Boolean.TRUE.equals(txTemplate.execute(status ->
                    repository.tryInsert(requestId, endpoint, fingerprint, PENDING, null, clock.nowMillis())));
        } catch (DuplicateKeyException e) {
            acquired = false;
        }
        if (!acquired) {
            return replay(requestId, endpoint, fingerprint, type);
        }
        try {
            T body = action.get();
            repository.updateResult(requestId, 200, toJson(body));
            return new Outcome(200, body, false);
        } catch (ApiException ex) {
            recordOutcome(requestId, ex.getStatus().value(),
                    Map.of("error", ex.getStatus().getReasonPhrase(), "message", ex.getMessage()));
            throw ex;
        } catch (RuntimeException ex) {
            recordOutcome(requestId, 500, Map.of("error", "Internal Server Error", "message", "internal error"));
            throw ex;
        }
    }

    private void recordOutcome(String requestId, int status, Object body) {
        try {
            // 业务异常会回滚外层事务，失败结果必须在独立事务提交，保证失败可幂等重放
            txTemplate.executeWithoutResult(txStatus ->
                    repository.updateResult(requestId, status, toJson(body)));
        } catch (Exception ignored) {
            // 幂等结果回填失败不应掩盖业务异常
        }
    }

    private <T> Outcome replay(String requestId, String endpoint, String fingerprint, Class<T> type) {
        long deadline = clock.nowMillis() + WAIT_MAX_MILLIS;
        IdempotentRepository.StoredRequest stored;
        do {
            stored = repository.find(requestId);
            if (stored == null) {
                // 占位事务尚不可见（并发），自旋等待
                sleepQuietly();
                continue;
            }
            if (!endpoint.equals(stored.endpoint()) || !fingerprint.equals(stored.fingerprint())) {
                throw new ConflictException("requestId 已用于不同业务参数的请求");
            }
            if (stored.httpStatus() != PENDING) {
                if (stored.httpStatus() != 200) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> body = (Map<String, String>) parseJson(stored.responseBody(), Map.class);
                    throw new ReplayedApiException(stored.httpStatus(),
                            body == null ? "request replayed error"
                                    : body.getOrDefault("message", "request replayed error"));
                }
                return new Outcome(200, parseJson(stored.responseBody(), type), true);
            }
            sleepQuietly();
        } while (clock.nowMillis() < deadline);
        throw new ConflictException("相同 requestId 的首次请求仍在处理中");
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String toJson(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> Object parseJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
