package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.error.BusinessException;
import com.example.starter.repo.IdempotencyRepository;
import com.example.starter.repo.IdempotencyRepository.IdempotencyRow;
import com.example.starter.web.dto.PlanResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 写操作幂等：指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果，同键异参 409。
 * 通过 JVM 内分段锁串行化同键并发，幂等记录与业务写入在同一事务内提交。
 */
@Service
public class IdempotencyService {

    private static final int LOCK_STRIPES = 64;

    private final IdempotencyRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Object[] stripes = new Object[LOCK_STRIPES];

    public IdempotencyService(IdempotencyRepository repository,
            TransactionTemplate transactionTemplate, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            stripes[i] = new Object();
        }
    }

    /**
     * 幂等执行写操作。
     *
     * @param action         操作类型（APPLY/ARRANGE/...）
     * @param requestId      幂等键
     * @param businessFields 全部业务字段（用于指纹）
     * @param business       业务逻辑（在事务内执行）
     * @return 首次执行结果；同键同参重放返回首次结果快照
     */
    public PlanResponse execute(String action, String requestId, List<String> businessFields,
            Supplier<PlanResponse> business) {
        String fingerprint = fingerprint(action, requestId, businessFields);
        synchronized (stripeFor(requestId)) {
            return transactionTemplate.execute(status -> {
                var existing = repository.findByRequestId(requestId);
                if (existing.isPresent()) {
                    IdempotencyRow row = existing.get();
                    if (!row.fingerprint().equals(fingerprint)) {
                        throw BusinessException.conflict("IDEMPOTENCY_CONFLICT",
                                "幂等键 " + requestId + " 已以不同业务参数提交");
                    }
                    return deserialize(row.responseBody());
                }
                PlanResponse response = business.get();
                repository.insert(requestId, fingerprint, action, response.id(),
                        serialize(response), LocalDateTime.now(clock));
                return response;
            });
        }
    }

    private Object stripeFor(String requestId) {
        return stripes[Math.floorMod(requestId.hashCode(), LOCK_STRIPES)];
    }

    /** 指纹：action + requestId + 全部业务字段的 SHA-256。 */
    static String fingerprint(String action, String requestId, List<String> businessFields) {
        StringBuilder material = new StringBuilder(action).append('|').append(requestId);
        for (String field : businessFields) {
            material.append('|').append(field == null ? "<null>" : field);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String serialize(PlanResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private PlanResponse deserialize(String body) {
        try {
            return objectMapper.readValue(body, PlanResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应快照反序列化失败", e);
        }
    }
}
