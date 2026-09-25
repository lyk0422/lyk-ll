package com.example.starter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 放行归档记录 JDBC 访问：每卡至多一条（uk_release_card）。
 */
@Repository
public class ReleaseRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReleaseRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 放行记录行。 */
    public record ReleaseRow(long id, long cardId, String releasedBy, String requestId, Instant archivedAt) {
    }

    public void insert(long cardId, String releasedBy, String requestId, Instant archivedAt) {
        jdbcTemplate.update(
                "INSERT INTO release_record (card_id, released_by, request_id, archived_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                cardId, releasedBy, requestId, Timestamp.from(archivedAt), Timestamp.from(archivedAt));
    }

    public ReleaseRow findByCard(long cardId) {
        List<ReleaseRow> rows = jdbcTemplate.query(
                "SELECT id, card_id, released_by, request_id, archived_at FROM release_record WHERE card_id = ?",
                (rs, n) -> new ReleaseRow(
                        rs.getLong("id"), rs.getLong("card_id"), rs.getString("released_by"),
                        rs.getString("request_id"), rs.getTimestamp("archived_at").toInstant()),
                cardId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
