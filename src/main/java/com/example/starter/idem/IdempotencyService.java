package com.example.starter.idem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import com.example.starter.error.IdempotencyConflictException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 写操作幂等控制：以「端点前缀 + requestId」为键，指纹覆盖 requestId 与全部业务字段。
 * 同键同参返回首次结果快照，同键异参抛出 {@link IdempotencyConflictException}（HTTP 409）。
 * JVM 内按键加锁串行化并发执行，数据库主键作为最终兜底约束。
 */
@Service
public class IdempotencyService {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;
    private final Clock clock;
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public IdempotencyService(JdbcClient jdbc, ObjectMapper objectMapper,
                              TransactionTemplate txTemplate, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.txTemplate = txTemplate;
        this.clock = clock;
    }

    /**
     * 在单事务内执行幂等写：无记录则执行业务并落快照；有记录则按指纹返回首次结果或抛 409。
     */
    public <T> T execute(String scope, String requestId, String fingerprint,
                         Supplier<T> business, Class<T> responseType) {
        String key = scope + ":" + requestId;
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return txTemplate.execute(status -> {
                Optional<IdemRow> existing = find(key);
                if (existing.isPresent()) {
                    IdemRow row = existing.get();
                    if (!row.fingerprint().equals(fingerprint)) {
                        throw new IdempotencyConflictException(
                                "requestId 已存在但业务参数不一致，指纹不匹配");
                    }
                    return readValue(row.responseJson(), responseType);
                }
                T result = business.get();
                insert(key, fingerprint, writeValue(result));
                return result;
            });
        } finally {
            lock.unlock();
        }
    }

    /**
     * 计算请求指纹：SHA-256(requestId 与全部业务字段按固定顺序拼接)。
     */
    public static String fingerprint(String... parts) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] hash = digest.digest(String.join("|", parts).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private Optional<IdemRow> find(String key) {
        return jdbc.sql("SELECT request_key, fingerprint, response_json FROM idempotency_record WHERE request_key = ?")
                .param(key)
                .query((rs, rowNum) -> new IdemRow(
                        rs.getString("request_key"),
                        rs.getString("fingerprint"),
                        rs.getString("response_json")))
                .optional();
    }

    private void insert(String key, String fingerprint, String responseJson) {
        jdbc.sql("INSERT INTO idempotency_record (request_key, fingerprint, response_json, created_at) VALUES (?, ?, ?, ?)")
                .params(key, fingerprint, responseJson, LocalDateTime.now(clock))
                .update();
    }

    private String writeValue(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应序列化失败", e);
        }
    }

    private <T> T readValue(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("幂等响应反序列化失败", e);
        }
    }

    private record IdemRow(String requestKey, String fingerprint, String responseJson) {
    }
}
