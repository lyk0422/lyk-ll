package com.example.starter.specimen.repo;

import com.example.starter.specimen.model.IdempotencyRecordRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 幂等记录表数据访问：request_id 主键保证同键并发只落一条，冲突由唯一约束兜底。
 */
@Repository
public class IdempotencyRepository {

    private static final RowMapper<IdempotencyRecordRow> MAPPER = (rs, n) -> new IdempotencyRecordRow(
            rs.getString("request_id"),
            rs.getString("operation"),
            rs.getString("fingerprint"),
            rs.getString("response_body"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<IdempotencyRecordRow> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM idempotency_record WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public void insert(String requestId, String operation, String fingerprint,
                       String responseBody, Instant createdAt) {
        jdbc.update("INSERT INTO idempotency_record (request_id, operation, fingerprint, response_body,"
                        + " created_at) VALUES (?,?,?,?,?)",
                requestId, operation, fingerprint, responseBody, Timestamp.from(createdAt));
    }
}
