package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 幂等请求记录仓储（分装、检测申请）。
 */
@Repository
public class IdempotentRequestRepository {

    private static final String COLUMNS =
            "id, request_id, request_kind, fingerprint, response_json, created_at";

    private static final RowMapper<IdempotentRequestRow> MAPPER = IdempotentRequestRepository::mapRow;

    private final JdbcTemplate jdbc;

    public IdempotentRequestRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查找首次请求记录。
     */
    public Optional<IdempotentRequestRow> find(String requestId) {
        List<IdempotentRequestRow> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM idempotent_request WHERE request_id = ?",
                MAPPER, requestId);
        return rows.stream().findFirst();
    }

    /**
     * 写入首次成功请求及其响应快照。
     */
    public void insert(String requestId, String requestKind, String fingerprint,
                       String responseJson, Instant now) {
        jdbc.update(
                "INSERT INTO idempotent_request (request_id, request_kind, fingerprint, response_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                requestId, requestKind, fingerprint, responseJson, Timestamp.from(now));
    }

    private static IdempotentRequestRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new IdempotentRequestRow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("request_kind"),
                rs.getString("fingerprint"),
                rs.getString("response_json"),
                rs.getTimestamp("created_at").toInstant());
    }
}
