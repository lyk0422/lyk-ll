package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.starter.error.ApiException;
import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.repo.IdempotencyRepository.IdempotencyRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 幂等处理服务：指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果；同键异参返回 409 且不改变业务状态；并发同键仅一个事务生效。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
                              TransactionTemplate transactionTemplate, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 计算请求指纹：requestId 与全部业务字段按顺序拼接后取 SHA-256。 */
    public static String fingerprint(String requestId, Object... businessFields) {
        StringBuilder sb = new StringBuilder(requestId);
        for (Object field : businessFields) {
            sb.append('|').append(String.valueOf(field));
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 在单事务内执行幂等业务：先查重，再执行业务动作并登记首次结果。
     * 业务动作抛出的异常会使整个事务（含幂等登记）回滚。
     */
    public <T> T execute(String endpoint, String requestId, String fingerprint,
                         Class<T> responseType, Supplier<T> action) {
        try {
            return transactionTemplate.execute(status -> {
                Optional<IdempotencyRecord> existing = repository.find(requestId);
                if (existing.isPresent()) {
                    return replay(existing.get(), endpoint, fingerprint, responseType);
                }
                T result = action.get();
                repository.insert(requestId, endpoint, fingerprint, 200,
                        serialize(result), OffsetDateTime.now(clock));
                return result;
            });
        } catch (DuplicateKeyException e) {
            // 并发同键：对方事务已登记，回滚后按首次结果重放或报 409
            IdempotencyRecord record = repository.find(requestId)
                    .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_RACE",
                            "幂等键并发冲突，请重试: " + requestId));
            return replay(record, endpoint, fingerprint, responseType);
        }
    }

    private <T> T replay(IdempotencyRecord record, String endpoint, String fingerprint,
                         Class<T> responseType) {
        if (!record.endpoint().equals(endpoint) || !record.fingerprint().equals(fingerprint)) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT",
                    "相同 requestId 已用于不同请求，拒绝重放: " + record.requestId());
        }
        try {
            return objectMapper.readValue(record.responseBody(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果反序列化失败: " + record.requestId(), e);
        }
    }

    private <T> String serialize(T result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("幂等结果序列化失败", e);
        }
    }
}
