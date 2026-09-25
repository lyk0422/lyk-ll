package com.example.starter.roaming.service;

import com.example.starter.roaming.error.ConflictException;
import com.example.starter.roaming.repo.IdempotentRequestRepository;
import com.example.starter.roaming.repo.IdempotentRequestRepository.RequestRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * requestId 幂等执行器。
 * <p>指纹覆盖 requestId 与全部业务字段：同键同参返回首次结果，同键异参返回 409，
 * 且不产生新会话或新计量。pending 登记与业务处理处于同一事务，失败随事务回滚。</p>
 */
@Component
public class IdempotencySupport {

    /** 首次成功响应的 HTTP 状态码类型。 */
    public record Result<T>(T body, int httpStatus, boolean replayed) {
    }

    private final IdempotentRequestRepository requestRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencySupport(IdempotentRequestRepository requestRepository,
                              ObjectMapper objectMapper, Clock clock) {
        this.requestRepository = requestRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 以 requestId 幂等执行业务。
     *
     * @param requestId   业务幂等键
     * @param requestType 请求类型
     * @param payload     完整请求体（参与指纹）
     * @param httpStatus  首次成功响应使用的 HTTP 状态码
     * @param bodyClass   响应体类型（回放时反序列化）
     * @param action      首次执行的业务动作
     */
    public <T> Result<T> execute(String requestId, String requestType, Object payload, int httpStatus,
                                 Class<T> bodyClass, Supplier<T> action) {
        String fingerprint = fingerprint(requestType, payload);
        RequestRow existing = requestRepository.find(requestId).orElse(null);
        if (existing == null) {
            return runFirstTime(requestId, requestType, fingerprint, httpStatus, bodyClass, action);
        }
        return replay(existing, fingerprint, bodyClass);
    }

    private <T> Result<T> runFirstTime(String requestId, String requestType, String fingerprint,
                                       int httpStatus, Class<T> bodyClass, Supplier<T> action) {
        try {
            requestRepository.insertPending(requestId, requestType, fingerprint, Instant.now(clock));
        } catch (DuplicateKeyException e) {
            // 并发同键：等待对方事务提交后按同参/异参处理。
            RequestRow raced = awaitCommitted(requestId);
            return replay(raced, fingerprint, bodyClass);
        }
        T body = action.get();
        requestRepository.complete(requestId, httpStatus, toJson(body));
        return new Result<>(body, httpStatus, false);
    }

    private RequestRow awaitCommitted(String requestId) {
        // 等待超时以真实墙钟为准（注入时钟在测试中可能被冻结），最长等待 10 秒。
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            RequestRow row = requestRepository.find(requestId).orElse(null);
            if (row != null && row.httpStatus() != null && row.httpStatus() > 0) {
                return row;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ConflictException("相同 requestId 的请求正在处理中");
            }
        }
        throw new ConflictException("相同 requestId 的请求正在处理中");
    }

    private <T> Result<T> replay(RequestRow existing, String fingerprint, Class<T> bodyClass) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ConflictException("相同 requestId 的请求参数与首次请求不一致");
        }
        if (existing.httpStatus() == null || existing.httpStatus() == 0) {
            RequestRow committed = awaitCommitted(existing.requestId());
            return new Result<>(fromJson(committed.responseBody(), bodyClass), committed.httpStatus(), true);
        }
        return new Result<>(fromJson(existing.responseBody(), bodyClass), existing.httpStatus(), true);
    }

    /**
     * 计算“请求类型＋完整业务字段（含 requestId）”的 SHA-256 指纹。
     */
    public String fingerprint(String requestType, Object payload) {
        String canonical = requestType + "|" + toJson(payload);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("请求体无法序列化", e);
        }
    }

    private <T> T fromJson(String json, Class<T> bodyClass) {
        try {
            return objectMapper.readValue(json, bodyClass);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("首次响应无法反序列化", e);
        }
    }
}
