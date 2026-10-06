package com.example.starter.dimming;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等执行器：写操作按 requestId 幂等。
 * 指纹 = SHA-256(requestId + 接口 + 全部业务字段的规范化JSON)。
 * 同键同参返回首次结果；同键异参返回409；并发同键在执行中返回409。
 * 业务执行失败时清除幂等占位，允许修正后重试。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 以幂等方式执行写操作。
     *
     * @param endpoint 接口标识
     * @param requestId 幂等请求标识
     * @param businessPayload 全部业务字段（参与指纹）
     * @param action 业务动作（在独立事务中执行）
     * @param responseType 响应类型
     */
    public <T> T execute(String endpoint, String requestId, Object businessPayload,
            Supplier<T> action, Class<T> responseType) {
        String fingerprint = fingerprint(endpoint, requestId, businessPayload);
        try {
            String rid = requestId;
            transactionTemplate.executeWithoutResult(tx ->
                    repository.insert(rid, endpoint, fingerprint, LocalDateTime.now(clock)));
        } catch (DuplicateKeyException duplicate) {
            return handleDuplicate(endpoint, requestId, fingerprint, responseType);
        }
        try {
            T result = action.get();
            String body = toJson(result);
            transactionTemplate.executeWithoutResult(tx ->
                    repository.complete(requestId, 200, body));
            return result;
        } catch (RuntimeException ex) {
            transactionTemplate.executeWithoutResult(tx -> repository.delete(requestId));
            throw ex;
        }
    }

    private <T> T handleDuplicate(String endpoint, String requestId, String fingerprint,
            Class<T> responseType) {
        IdempotencyRepository.IdempotencyRow existing = repository.findByRequestId(requestId)
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_CONFLICT",
                        "请求标识冲突且无法读取首次记录: " + requestId));
        if (!existing.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "同一 requestId 携带不同业务参数: " + requestId);
        }
        if (existing.responseBody() == null) {
            throw ApiException.conflict("REQUEST_IN_FLIGHT",
                    "相同请求正在执行中，请稍后重试: " + requestId);
        }
        try {
            return objectMapper.readValue(existing.responseBody(), responseType);
        } catch (Exception ex) {
            throw new IllegalStateException("幂等结果反序列化失败", ex);
        }
    }

    private String fingerprint(String endpoint, String requestId, Object payload) {
        String material = requestId + "|" + endpoint + "|" + toJson(payload);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化失败", ex);
        }
    }
}
