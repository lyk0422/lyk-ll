package com.example.starter.tailings.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import com.example.starter.tailings.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 幂等执行器：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果快照，同键异参返回 409。
 * 调用方须在事务内使用，保证业务写入与幂等记录同生共死。
 */
@Component
public class IdempotencyService {

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * 幂等执行。
     *
     * @param requestId       幂等请求ID
     * @param canonicalFields 全部业务字段的规范化串（参与指纹）
     * @param type            响应类型
     * @param action          首次执行的业务动作
     * @return 首次执行结果或重放的首次结果
     */
    public <T> T execute(String requestId, String canonicalFields, Class<T> type, Supplier<T> action) {
        String fingerprint = sha256(requestId + "|" + canonicalFields);
        var existing = repository.find(requestId);
        if (existing.isPresent()) {
            IdempotencyRepository.IdempotencyRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict("requestId 已使用且业务参数不一致");
            }
            return read(row.responseBody(), type);
        }
        T result = action.get();
        String body = write(result);
        try {
            repository.insert(requestId, fingerprint, body);
        } catch (DuplicateKeyException e) {
            // 并发同键：以先提交的记录为准
            IdempotencyRepository.IdempotencyRow row = repository.find(requestId)
                    .orElseThrow(() -> e);
            if (!row.fingerprint().equals(fingerprint)) {
                throw BusinessException.conflict("requestId 已使用且业务参数不一致");
            }
            return read(row.responseBody(), type);
        }
        return result;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }
}
