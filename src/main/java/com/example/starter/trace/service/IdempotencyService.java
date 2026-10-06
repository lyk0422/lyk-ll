package com.example.starter.trace.service;

import com.example.starter.trace.repo.IdempotencyRepository;
import com.example.starter.trace.repo.IdempotencyRepository.IdempotencyRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 写操作幂等：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果快照，同键异参拒绝（409）。
 */
@Service
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 计算请求指纹：SHA-256(requestId | 业务字段...)。
     */
    public static String fingerprint(String requestId, String... businessFields) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        digest.update(requestId.getBytes(StandardCharsets.UTF_8));
        for (String field : businessFields) {
            digest.update((byte) 0x1F);
            digest.update(field.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 在调用方事务内执行幂等写：已存在同键记录时按指纹决定重放或拒绝，否则执行动作并落快照。
     *
     * @param requestId   幂等请求 ID
     * @param fingerprint 请求指纹（覆盖 requestId 与全部业务字段）
     * @param type        响应类型，用于快照反序列化
     * @param action      首次执行的业务动作
     * @return 首次执行结果或首次结果快照
     */
    public <T> T execute(String requestId, String fingerprint, Class<T> type, Supplier<T> action) {
        Optional<IdempotencyRow> existing = repository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, type);
        }
        T result = action.get();
        String body = serialize(result);
        try {
            repository.insert(requestId, fingerprint, HttpStatus.OK.value(), body,
                    LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            // 并发同键：以先入库的记录为准
            IdempotencyRow row = repository.findByRequestId(requestId)
                    .orElseThrow(() -> BusinessException.conflict("幂等记录冲突，请重试"));
            return replay(row, fingerprint, type);
        }
        return result;
    }

    private <T> T replay(IdempotencyRow row, String fingerprint, Class<T> type) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BusinessException.conflict("相同 requestId 已用于不同业务参数");
        }
        return deserialize(row.responseBody(), type);
    }

    private <T> T deserialize(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等快照反序列化失败", e);
        }
    }

    private String serialize(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("幂等快照序列化失败", e);
        }
    }
}
