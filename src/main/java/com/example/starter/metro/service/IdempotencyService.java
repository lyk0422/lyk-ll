package com.example.starter.metro.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

import com.example.starter.metro.MetroException;
import com.example.starter.metro.repo.IdempotencyRepository;
import com.example.starter.metro.repo.IdempotencyRepository.IdempotencyRow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * 幂等执行器：以 requestId 为幂等键，指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果；同键异参返回 409 且不重复入账。
 * 本类方法不开启事务：占位声明、业务执行、完成标记各自独立提交，
 * 并发同键请求通过主键占位串行化，后到的请求等待首个请求完成后重放结果。
 */
@Service
public class IdempotencyService {

    private static final long WAIT_TIMEOUT_MILLIS = 10_000L;
    private static final long POLL_INTERVAL_MILLIS = 50L;

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(IdempotencyRepository repository, ObjectMapper objectMapper,
            Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 计算幂等指纹：SHA-256(requestId + 业务字段串)。
     */
    public String fingerprint(String requestId, String businessFields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((requestId + "|" + businessFields).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 幂等执行：返回首次成功响应的 JSON 报文（重放时原样返回）。
     *
     * @param requestId   幂等键
     * @param fingerprint 幂等指纹
     * @param action      业务动作，其返回值序列化为 JSON 作为响应
     */
    public String execute(String requestId, String fingerprint, Supplier<Object> action) {
        Optional<IdempotencyRow> existing = repository.find(requestId);
        if (existing.isPresent()) {
            return replayOrWait(existing.get(), fingerprint);
        }
        try {
            repository.insertClaim(requestId, fingerprint, LocalDateTime.now(clock));
        } catch (DuplicateKeyException e) {
            return waitForCompletion(requestId, fingerprint);
        }
        try {
            Object result = action.get();
            String body;
            try {
                body = objectMapper.writeValueAsString(result);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("响应序列化失败", e);
            }
            repository.markCompleted(requestId, body, LocalDateTime.now(clock));
            return body;
        } catch (RuntimeException e) {
            repository.delete(requestId);
            throw e;
        }
    }

    private String replayOrWait(IdempotencyRow row, String fingerprint) {
        if (IdempotencyRepository.STATUS_COMPLETED.equals(row.status())) {
            return replay(row, fingerprint);
        }
        return waitForCompletion(row.requestId(), fingerprint);
    }

    private String replay(IdempotencyRow row, String fingerprint) {
        if (!row.fingerprint().equals(fingerprint)) {
            throw new MetroException.IdempotencyConflict(row.requestId());
        }
        return row.responseBody();
    }

    private String waitForCompletion(String requestId, String fingerprint) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Optional<IdempotencyRow> row = repository.find(requestId);
            if (row.isPresent()
                    && IdempotencyRepository.STATUS_COMPLETED.equals(row.get().status())) {
                return replay(row.get(), fingerprint);
            }
            if (row.isEmpty()) {
                // 首个请求业务失败已释放占位，按新请求重新执行由调用方重试；此处视为冲突处理中
                throw new MetroException.IdempotencyConflict(requestId);
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MetroException(500, "等待幂等结果时被中断");
            }
        }
        throw new MetroException(409, "相同 requestId 的请求正在处理中: " + requestId);
    }
}
