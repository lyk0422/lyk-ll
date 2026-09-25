package com.example.starter.service;

import com.example.starter.domain.RequestType;
import com.example.starter.po.IdempotentRequest;
import com.example.starter.repo.IdempotentRepository;
import com.example.starter.web.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Supplier;

/**
 * 幂等执行器：调节令、评估、归档、温度上报统一经此处理。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>同键同参：返回首次结果（响应体原样返回，replay 标记为 true）；</li>
 *   <li>同键异参：返回 409，且不执行任何业务写入；</li>
 *   <li>同键并发：后到者等待先到者完成后再按上述规则判定；</li>
 *   <li>业务失败：释放受理记录，允许修正后重试（仅成功结果参与重放）。</li>
 * </ul>
 */
@Component
public class IdempotencyExecutor {

    private static final long WAIT_TIMEOUT_MILLIS = 10_000;
    private static final long WAIT_INTERVAL_MILLIS = 50;

    private final IdempotentRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate requiresNew;

    public IdempotencyExecutor(IdempotentRepository repository, ObjectMapper objectMapper, Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 以幂等方式执行业务。{@code business} 由调用方保证运行在事务内。
     */
    public <T> T execute(String requestId, RequestType type, String fingerprint, long stationId,
                         Class<T> responseType, Supplier<T> business) {
        while (true) {
            boolean claimed = Boolean.TRUE.equals(requiresNew.execute(status ->
                    repository.claim(requestId, type, fingerprint, stationId, Instant.now(clock))));
            if (claimed) {
                return runFirst(requestId, responseType, business);
            }
            IdempotentRequest existing = awaitCompletion(requestId);
            if (existing == null) {
                // 先到请求失败并释放了受理记录，本轮重新抢占
                continue;
            }
            return replay(existing, fingerprint, responseType);
        }
    }

    private <T> T runFirst(String requestId, Class<T> responseType, Supplier<T> business) {
        try {
            T result = business.get();
            String body = objectMapper.writeValueAsString(result);
            requiresNew.executeWithoutResult(status ->
                    repository.complete(requestId, 200, body, Instant.now(clock)));
            return result;
        } catch (RuntimeException e) {
            requiresNew.executeWithoutResult(status -> repository.release(requestId));
            throw e;
        } catch (JsonProcessingException e) {
            requiresNew.executeWithoutResult(status -> repository.release(requestId));
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T replay(IdempotentRequest existing, String fingerprint, Class<T> responseType) {
        if (!existing.getFingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENT_CONFLICT",
                    "requestId 已受理但业务字段不一致，已拒绝且未改变任何状态");
        }
        requiresNew.executeWithoutResult(status -> repository.incrementReplay(existing.getRequestId()));
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(existing.getResponseBody());
            node.put("replay", true);
            return objectMapper.treeToValue(node, responseType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    /**
     * 等待先到请求完成；返回 null 表示其失败并释放了受理记录。
     */
    private IdempotentRequest awaitCompletion(String requestId) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (true) {
            IdempotentRequest existing = repository.find(requestId);
            if (existing == null) {
                return null;
            }
            if ("COMPLETED".equals(existing.getStatus())) {
                return existing;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw ApiException.conflict("REQUEST_IN_FLIGHT", "相同 requestId 的请求正在处理中，请稍后重试");
            }
            try {
                Thread.sleep(WAIT_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("REQUEST_IN_FLIGHT", "等待幂等请求完成时被中断");
            }
        }
    }
}
