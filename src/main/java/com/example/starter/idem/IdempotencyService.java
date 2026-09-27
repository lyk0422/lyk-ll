package com.example.starter.idem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.error.ApiException;

/**
 * 幂等请求服务：指纹覆盖 requestId 与全部业务字段；
 * 同键同参返回首次结果，同键异参返回 409。
 */
@Component
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;
    private final Clock clock;

    public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate txTemplate, Clock businessClock) {
        this.jdbc = jdbc;
        this.txTemplate = txTemplate;
        this.clock = businessClock;
    }

    /**
     * 计算请求指纹：SHA-256(endpoint | requestId | 业务字段...)。
     */
    public static String fingerprint(String endpoint, String requestId, String... businessFields) {
        StringBuilder sb = new StringBuilder(endpoint).append('|').append(requestId);
        for (String field : businessFields) {
            sb.append('|').append(field == null ? "" : field);
        }
        return sha256Hex(sb.toString());
    }

    /**
     * 计算结构化请求指纹：长度前缀编码后做 SHA-256，
     * 字段边界无歧义，合法文本含分隔符（如 '|'、':'）也可区分。
     */
    public static String structuredFingerprint(String endpoint, String requestId, List<String> businessFields) {
        StringBuilder sb = new StringBuilder();
        appendLengthPrefixed(sb, endpoint);
        appendLengthPrefixed(sb, requestId);
        for (String field : businessFields) {
            appendLengthPrefixed(sb, field);
        }
        return sha256Hex(sb.toString());
    }

    private static void appendLengthPrefixed(StringBuilder sb, String field) {
        String value = field == null ? "" : field;
        sb.append(value.length()).append(':').append(value).append(';');
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 幂等执行：首次执行动作并登记响应快照；重放同参返回快照，异参抛 409。
     *
     * @param endpoint    接口标识
     * @param requestId   幂等请求号
     * @param fingerprint 请求指纹（须覆盖 requestId 与全部业务字段）
     * @param action      首次执行的业务动作，在事务内运行
     * @return 首次或重放的响应快照
     */
    public StoredResponse handle(String endpoint, String requestId, String fingerprint,
                                 Supplier<StoredResponse> action) {
        StoredResponse existing = find(requestId);
        if (existing != null) {
            return replay(requestId, fingerprint, existing);
        }
        try {
            return txTemplate.execute(status -> {
                StoredResponse response = action.get();
                jdbc.update(
                        "INSERT INTO idempotent_request (request_id, endpoint, fingerprint, response_body, http_status, created_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)",
                        requestId, endpoint, fingerprint, response.body(), response.status(),
                        Timestamp.from(Instant.now(clock)));
                return response;
            });
        } catch (DuplicateKeyException e) {
            // 并发同键：本事务已回滚，读取先完成一方的登记结果
            StoredResponse stored = find(requestId);
            if (stored == null) {
                throw e;
            }
            return replay(requestId, fingerprint, stored);
        }
    }

    private StoredResponse replay(String requestId, String fingerprint, StoredResponse existing) {
        String storedFingerprint = jdbc.queryForObject(
                "SELECT fingerprint FROM idempotent_request WHERE request_id = ?", String.class, requestId);
        if (!fingerprint.equals(storedFingerprint)) {
            throw ApiException.conflict("IDEMPOTENT_MISMATCH",
                    "requestId 已登记但业务参数不一致: " + requestId);
        }
        return existing;
    }

    private StoredResponse find(String requestId) {
        return jdbc.query(
                "SELECT http_status, response_body FROM idempotent_request WHERE request_id = ?",
                (rs, rowNum) -> new StoredResponse(rs.getInt(1), rs.getString(2)),
                requestId).stream().findFirst().orElse(null);
    }
}
