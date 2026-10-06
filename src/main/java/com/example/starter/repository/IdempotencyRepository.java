package com.example.starter.repository;

import com.example.starter.model.IdempotencyRow;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 幂等记录数据访问：request_id 主键，并发下同键仅一条。
 */
@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询。
     */
    public Optional<IdempotencyRow> find(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT request_id, endpoint, fingerprint, response_body FROM idempotency_record"
                        + " WHERE request_id = ?",
                (rs, n) -> new IdempotencyRow(
                        rs.getString("request_id"),
                        rs.getString("endpoint"),
                        rs.getString("fingerprint"),
                        rs.getString("response_body")),
                requestId);
        return rows.stream().findFirst();
    }

    /**
     * 占位插入幂等记录（与业务写入同事务）；同键并发时抛出 DuplicateKeyException。
     */
    public void insert(String requestId, String endpoint, String fingerprint, LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO idempotency_record(request_id, endpoint, fingerprint, created_at)"
                        + " VALUES (?,?,?,?)",
                requestId, endpoint, fingerprint, Timestamp.valueOf(createdAt));
    }

    /**
     * 回填首次成功响应快照。
     */
    public void updateResponse(String requestId, String responseBody) {
        jdbc.update(
                "UPDATE idempotency_record SET response_body = ? WHERE request_id = ?",
                responseBody, requestId);
    }
}
