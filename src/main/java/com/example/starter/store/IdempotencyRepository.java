package com.example.starter.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等记录数据访问：requestId 全局唯一，指纹覆盖 requestId 与全部业务字段。
 */
@Repository
public class IdempotencyRepository {

    /** 幂等记录行。 */
    public record IdemRow(String requestId, String operation, String fingerprint, String responseBody) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按请求号查询幂等记录。 */
    public Optional<IdemRow> find(String requestId) {
        List<IdemRow> rows = jdbc.query(
                "SELECT request_id, operation, fingerprint, response_body FROM idempotency_record WHERE request_id = ?",
                (rs, n) -> new IdemRow(
                        rs.getString("request_id"),
                        rs.getString("operation"),
                        rs.getString("fingerprint"),
                        rs.getString("response_body")),
                requestId);
        return rows.stream().findFirst();
    }

    /** 插入幂等记录；并发下同键由主键约束拦截并抛出 DuplicateKeyException。 */
    public void insert(String requestId, String operation, String fingerprint,
                       String responseBody, LocalDateTime now) {
        jdbc.update("INSERT INTO idempotency_record (request_id, operation, fingerprint, response_body, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                requestId, operation, fingerprint, responseBody, java.sql.Timestamp.valueOf(now));
    }

    /** 回写首次成功响应报文（与业务写入同事务）。 */
    public void updateResponse(String requestId, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }

    /** 计算请求指纹：SHA-256(operation + requestId + 全部业务字段)。 */
    public static String fingerprint(String operation, String requestId, Object... businessFields) {
        StringBuilder sb = new StringBuilder(operation).append('|').append(requestId);
        for (Object field : businessFields) {
            sb.append('|').append(field == null ? "<null>" : field.toString());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 判断是否为并发/重复插入导致的唯一键冲突。 */
    public static boolean isDuplicateKey(RuntimeException ex) {
        return ex instanceof DuplicateKeyException;
    }
}
