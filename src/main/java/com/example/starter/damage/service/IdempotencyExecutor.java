package com.example.starter.damage.service;

import com.example.starter.damage.api.ApiException;
import com.example.starter.damage.store.IdempotencyRepository;
import com.example.starter.damage.store.IdempotencyRepository.IdemRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 幂等执行器：requestId 占位插入保证并发唯一受理；
 * 指纹覆盖 requestId 与全部业务字段，同键同参返回首次结果，同键异参返回 409。
 */
@Component
public class IdempotencyExecutor {

    private static final long WAIT_TIMEOUT_MILLIS = 10_000;
    private static final long POLL_INTERVAL_MILLIS = 50;

    private final IdempotencyRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyExecutor(IdempotencyRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 以幂等方式执行写操作。
     *
     * @param endpoint 入口标识（参与指纹计算）
     * @param requestId 幂等请求 ID
     * @param payload 全部业务字段（参与指纹计算）
     * @param type 响应类型
     * @param action 业务动作（在事务内执行，并负责写入完成标记）
     * @return 首次执行结果或重放结果
     */
    public <T> T execute(String endpoint, String requestId, Object payload, Class<T> type, Supplier<T> action) {
        String fingerprint = fingerprint(endpoint, requestId, payload);
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<IdemRow> existing = repository.find(requestId);
            if (existing.isEmpty()) {
                try {
                    repository.insertInProgress(requestId, endpoint, fingerprint, LocalDateTime.now(clock));
                } catch (DuplicateKeyException e) {
                    continue; // 并发下由主键仲裁，重新读取
                }
                try {
                    return action.get();
                } catch (RuntimeException e) {
                    repository.delete(requestId); // 业务失败释放占位，允许重试
                    throw e;
                }
            }
            IdemRow row = existing.get();
            if (!row.fingerprint().equals(fingerprint)) {
                throw ApiException.conflict("相同 requestId 的请求参数不一致，已拒绝");
            }
            if (!"COMPLETED".equals(row.status())) {
                row = awaitCompletion(requestId);
                if (row == null) {
                    continue; // 首次执行失败已释放占位，重试
                }
                if (!row.fingerprint().equals(fingerprint)) {
                    throw ApiException.conflict("相同 requestId 的请求参数不一致，已拒绝");
                }
            }
            return deserialize(row.responseJson(), type);
        }
        throw ApiException.conflict("相同 requestId 请求正在处理中，请稍后重试");
    }

    /**
     * 在业务事务内标记完成并写入首次响应报文（与业务数据同事务提交）。
     */
    public void completeInTransaction(String requestId, Object response) {
        try {
            repository.complete(requestId, objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private IdemRow awaitCompletion(String requestId) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            Optional<IdemRow> row = repository.find(requestId);
            if (row.isEmpty()) {
                return null;
            }
            if ("COMPLETED".equals(row.get().status())) {
                return row.get();
            }
            try {
                Thread.sleep(POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ApiException.conflict("等待幂等结果时被中断");
            }
        }
        throw ApiException.conflict("相同 requestId 请求正在处理中，请稍后重试");
    }

    private <T> T deserialize(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private String fingerprint(String endpoint, String requestId, Object payload) {
        try {
            // 结构化信封：endpoint/requestId/payload 各自为独立 JSON 字段，
            // 合法文本即使包含 "|" 等分隔符也无法跨字段伪造，指纹无歧义。
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("endpoint", endpoint);
            envelope.put("requestId", requestId);
            envelope.put("payload", payload);
            String canonical = objectMapper.writeValueAsString(envelope);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        } catch (Exception e) {
            throw new IllegalStateException("幂等指纹计算失败", e);
        }
    }
}
