package com.example.starter.idem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
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
        return sha256(sb);
    }

    /**
     * 结构化指纹：每个业务片段以“长度:”前缀编码；集合额外写入元素个数并逐元素长度前缀编码。
     * 即便合法文本本身含有分隔符（如 "|"、","、":"），不同结构也不可能产生相同指纹。
     *
     * @param endpoint 接口标识
     * @param requestId 幂等请求号
     * @param parts 业务片段，元素可为 String/Number/Instant 或其 Collection/数组
     */
    public static String fingerprintStructured(String endpoint, String requestId, java.util.List<Object> parts) {
        StringBuilder sb = new StringBuilder(endpoint).append('|').append(requestId);
        for (Object part : parts) {
            appendStructured(sb, part);
        }
        return sha256(sb);
    }

    private static void appendStructured(StringBuilder sb, Object part) {
        if (part instanceof java.util.Collection<?> collection) {
            sb.append("|#").append(collection.size());
            for (Object element : collection) {
                String v = String.valueOf(element);
                sb.append("|").append(v.length()).append(":").append(v);
            }
        } else if (part != null && part.getClass().isArray()) {
            Object[] array = (Object[]) part;
            sb.append("|#").append(array.length);
            for (Object element : array) {
                String v = String.valueOf(element);
                sb.append("|").append(v.length()).append(":").append(v);
            }
        } else {
            String v = String.valueOf(part);
            sb.append("|").append(v.length()).append(":").append(v);
        }
    }

    private static String sha256(StringBuilder sb) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
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
