package com.example.starter.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写操作幂等执行器。
 *
 * <p>语义：指纹覆盖 requestId 与全部业务字段；同键同参返回首次结果，同键异参返回 409。
 *
 * <p>并发保证：按 requestId 加 JVM 级锁，且事务在锁内提交（TransactionTemplate），
 * 因此并发同键请求只会有一个真正执行业务动作，其余读取已提交的首次结果；
 * 数据库 request_id 主键作为兜底约束。业务动作与幂等记录在同一事务内提交或回滚。
 */
@Component
public class IdempotentExecutor {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public IdempotentExecutor(JdbcClient jdbc, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * 以幂等方式执行写操作。
     *
     * @param requestId 请求标识，非空
     * @param businessPayload 全部业务字段（含路径变量），用于计算指纹
     * @param responseType 响应类型，用于回放时反序列化
     * @param action 业务动作，仅在首次请求时执行
     * @return 首次执行结果（回放时返回首次结果）
     */
    public <T> T execute(String requestId, Object businessPayload, Class<T> responseType, Supplier<T> action) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("requestId 不能为空");
        }
        String fingerprint = fingerprint(requestId, businessPayload);
        Object lock = locks.computeIfAbsent(requestId, key -> new Object());
        synchronized (lock) {
            return transactionTemplate.execute(status -> {
                Optional<Stored> existing = find(requestId);
                if (existing.isPresent()) {
                    Stored stored = existing.get();
                    if (stored.fingerprint().equals(fingerprint)) {
                        return read(stored.responseBody(), responseType);
                    }
                    throw ApiException.conflict("相同 requestId 已使用且业务参数不一致");
                }
                T result = action.get();
                jdbc.sql("INSERT INTO idempotency_record (request_id, fingerprint, response_body, created_at)"
                                + " VALUES (?, ?, ?, ?)")
                        .params(requestId, fingerprint, write(result), LocalDateTime.now(clock))
                        .update();
                return result;
            });
        }
    }

    private Optional<Stored> find(String requestId) {
        return jdbc.sql("SELECT fingerprint, response_body FROM idempotency_record WHERE request_id = ?")
                .param(requestId)
                .query((rs, rowNum) -> new Stored(rs.getString(1), rs.getString(2)))
                .optional();
    }

    /**
     * 计算指纹：SHA-256(requestId + 业务字段 JSON)。同一请求类的字段顺序固定，结果确定。
     */
    private String fingerprint(String requestId, Object businessPayload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(requestId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(write(businessPayload).getBytes(StandardCharsets.UTF_8));
            byte[] hash = digest.digest();
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

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private <T> T read(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等回放反序列化失败", e);
        }
    }

    private record Stored(String fingerprint, String responseBody) {
    }
}
