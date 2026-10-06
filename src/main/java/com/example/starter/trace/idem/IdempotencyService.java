package com.example.starter.trace.idem;

import com.example.starter.trace.BizException;
import com.example.starter.trace.repo.IdempotencyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * 写操作幂等控制：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参 409。
 * 采用先占位（pending）再执行业务、最后回写响应的顺序，
 * 并发重复请求在幂等键主键上串行化，不会重复落业务数据。
 */
@Component
public class IdempotencyService {

    private static final int PENDING_STATUS = 0;

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 在调用方事务内执行幂等写操作。
     *
     * @param operation         操作类型
     * @param requestId         幂等键
     * @param fingerprintSource 全部业务字段（含路径变量），参与指纹计算
     * @param responseType      响应类型，用于重放反序列化
     * @param action            首次执行的业务动作
     */
    public <T> T execute(String operation, String requestId, Object fingerprintSource,
                         Class<T> responseType, Supplier<T> action) {
        String fingerprint = fingerprint(operation, requestId, fingerprintSource);
        var existing = repository.findByRequestId(requestId);
        if (existing.isPresent()) {
            return replay(existing.get(), fingerprint, responseType);
        }
        boolean occupied = repository.tryInsert(requestId, fingerprint, operation,
                PENDING_STATUS, "", LocalDateTime.now(clock));
        if (!occupied) {
            // 并发首次请求已提交：直接重放首次结果，当前事务未写业务数据
            var committed = repository.findByRequestId(requestId)
                    .orElseThrow(() -> BizException.conflict("幂等记录状态异常"));
            return replay(committed, fingerprint, responseType);
        }
        T result = action.get();
        repository.updateResponse(requestId, 200, toJson(result));
        return result;
    }

    /**
     * 命中已有幂等记录：同参返回首次结果，异参 409。
     */
    private <T> T replay(IdempotencyRepository.IdempotencyRow row, String fingerprint,
                         Class<T> responseType) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw BizException.conflict("相同 requestId 携带不同业务参数");
        }
        if (row.responseStatus() == PENDING_STATUS) {
            throw BizException.conflict("相同 requestId 的请求正在处理中");
        }
        try {
            return objectMapper.readValue(row.responseBody(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("幂等记录反序列化失败", e);
        }
    }

    private String fingerprint(String operation, String requestId, Object fingerprintSource) {
        String material = operation + '|' + requestId + '|' + toJson(fingerprintSource);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }
}
