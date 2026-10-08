package com.example.starter.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 写操作幂等控制：以 requestId 为唯一键，指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参返回 409。幂等记录与业务写入在同一事务内提交。
 */
@Service
public class IdempotencyService {

    private static final int MAX_ATTEMPTS = 100;
    private static final long RETRY_SLEEP_MILLIS = 50L;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbcTemplate,
                              TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper,
                              Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 幂等执行结果：HTTP 状态码、JSON 响应体、是否为首次结果的回放。
     */
    public record StoredResponse(int status, String body, boolean replayed) {
    }

    /**
     * 业务动作结果：HTTP 状态码与响应体对象；用于已提交业务变更但需返回非 200 的场景
     * （如回执恰满时限已升级，升级落库且首次结果为 409）。
     */
    public record ActionOutcome(int status, Object body) {
    }

    /**
     * 在事务内执行写操作并记录幂等结果；requestId 冲突时按指纹判定回放或 409。
     *
     * @param requestId   请求唯一键
     * @param fingerprint 覆盖 requestId 与全部业务字段的指纹
     * @param action      业务动作，返回将被序列化为 JSON 的结果对象（HTTP 200）
     */
    public StoredResponse execute(String requestId, String fingerprint, Supplier<Object> action) {
        return executeWithStatus(requestId, fingerprint,
                () -> new ActionOutcome(200, action.get()));
    }

    /**
     * 在事务内执行写操作并记录幂等结果，业务动作可指定首次响应的 HTTP 状态码。
     */
    public StoredResponse executeWithStatus(String requestId, String fingerprint,
                                            Supplier<ActionOutcome> action) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                return transactionTemplate.execute(status -> {
                    jdbcTemplate.update(
                            "INSERT INTO idempotency_record(request_id, fingerprint, http_status, response_body, created_at)"
                                    + " VALUES (?, ?, NULL, NULL, ?)",
                            requestId, fingerprint, Timestamp.from(clock.instant()));
                    ActionOutcome outcome = action.get();
                    String body = toJson(outcome.body());
                    jdbcTemplate.update(
                            "UPDATE idempotency_record SET http_status = ?, response_body = ? WHERE request_id = ?",
                            outcome.status(), body, requestId);
                    return new StoredResponse(outcome.status(), body, false);
                });
            } catch (DuplicateKeyException duplicate) {
                StoredResponse replayed = resolveConflict(requestId, fingerprint);
                if (replayed != null) {
                    return replayed;
                }
                sleep();
            }
        }
        throw ApiException.conflict("相同 requestId 的请求正在处理中，请稍后重试");
    }

    /**
     * 计算覆盖全部给定字段的指纹（SHA-256 十六进制）。
     */
    public static String fingerprint(Object... parts) {
        StringBuilder joined = new StringBuilder();
        for (Object part : parts) {
            joined.append(String.valueOf(part)).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(joined.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private StoredResponse resolveConflict(String requestId, String fingerprint) {
        List<StoredRow> rows = jdbcTemplate.query(
                "SELECT fingerprint, http_status, response_body FROM idempotency_record WHERE request_id = ?",
                (rs, rowNum) -> new StoredRow(rs.getString("fingerprint"),
                        (Integer) rs.getObject("http_status"), rs.getString("response_body")),
                requestId);
        if (rows.isEmpty()) {
            // 首个事务已回滚，允许重试插入。
            return null;
        }
        StoredRow row = rows.get(0);
        if (!fingerprint.equals(row.fingerprint())) {
            throw ApiException.conflict("requestId 已存在但业务参数不一致");
        }
        if (row.body() == null || row.status() == null) {
            // 首个事务尚未提交，等待后重试。
            return null;
        }
        return new StoredResponse(row.status(), row.body(), true);
    }

    private record StoredRow(String fingerprint, Integer status, String body) {
    }

    private String toJson(Object result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("响应序列化失败", e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(RETRY_SLEEP_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("请求被中断");
        }
    }
}
