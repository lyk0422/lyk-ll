package com.example.starter.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.domain.IdemRecord;
import com.example.starter.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 写操作幂等：指纹 = SHA-256(requestId + 全部业务字段的规范化JSON)。
 * 同键同参返回首次结果（状态码与报文原样回放），同键异参抛 409。
 * JVM 内按 requestId 串行化，request_id 唯一约束作数据库兜底；
 * 仅成功结果被记录，业务异常不落幂等记录、可安全重试。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final TransactionTemplate txTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public IdempotencyService(IdempotencyRepository repository, TransactionTemplate txTemplate,
                              ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.txTemplate = txTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public ServiceResult execute(String endpoint, String requestId, Object businessPayload,
                                 Supplier<ServiceResult> business) {
        String fingerprint = fingerprint(requestId, businessPayload);
        Object lock = locks.computeIfAbsent(requestId, k -> new Object());
        synchronized (lock) {
            try {
                return txTemplate.execute(tx -> {
                    Optional<IdemRecord> existing = repository.findByRequestId(requestId);
                    if (existing.isPresent()) {
                        IdemRecord record = existing.get();
                        if (!record.fingerprint().equals(fingerprint)) {
                            throw new BusinessException(409, "IDEMPOTENCY_CONFLICT",
                                    "相同requestId携带不同业务字段");
                        }
                        return new ServiceResult(record.responseStatus(), readJson(record.responseBody()));
                    }
                    ServiceResult result = business.get();
                    try {
                        repository.insert(requestId, fingerprint, endpoint, result.status(),
                                writeJson(result.body()), LocalDateTime.now(clock));
                    } catch (DuplicateKeyException e) {
                        // 数据库唯一约束兜底：并发下已有同键记录，回读比对
                        IdemRecord record = repository.findByRequestId(requestId)
                                .orElseThrow(() -> e);
                        if (!record.fingerprint().equals(fingerprint)) {
                            throw new BusinessException(409, "IDEMPOTENCY_CONFLICT",
                                    "相同requestId携带不同业务字段");
                        }
                        return new ServiceResult(record.responseStatus(), readJson(record.responseBody()));
                    }
                    return result;
                });
            } finally {
                locks.remove(requestId, lock);
            }
        }
    }

    private String fingerprint(String requestId, Object payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = requestId + "\n" + writeJson(payload);
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private Object readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化失败", e);
        }
    }
}
