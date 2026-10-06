package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 写操作幂等记录数据访问。
 */
@Repository
public class IdempotencyRepository {

    /** 幂等记录行。 */
    public record IdempotencyRow(String requestId, String endpoint, String fingerprint,
                                 int responseStatus, String responseBody, LocalDateTime createdAt) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRow> findByRequestId(String requestId) {
        return jdbc.query("SELECT request_id, endpoint, fingerprint, response_status, response_body, created_at "
                + "FROM idempotency_record WHERE request_id = ?", (rs, n) -> map(rs), requestId)
                .stream().findFirst();
    }

    /** 抢占式写入幂等键；已存在返回 false。 */
    public boolean tryInsert(IdempotencyRow row) {
        try {
            jdbc.update("INSERT INTO idempotency_record (request_id, endpoint, fingerprint, response_status, "
                            + "response_body, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    row.requestId(), row.endpoint(), row.fingerprint(), row.responseStatus(),
                    row.responseBody(), Timestamp.valueOf(row.createdAt()));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void updateResponse(String requestId, int responseStatus, String responseBody) {
        jdbc.update("UPDATE idempotency_record SET response_status = ?, response_body = ? WHERE request_id = ?",
                responseStatus, responseBody, requestId);
    }

    private IdempotencyRow map(ResultSet rs) throws SQLException {
        return new IdempotencyRow(rs.getString("request_id"), rs.getString("endpoint"),
                rs.getString("fingerprint"), rs.getInt("response_status"), rs.getString("response_body"),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
