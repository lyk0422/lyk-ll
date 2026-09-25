package com.example.starter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 自检互检记录 JDBC 访问：每卡至多一条（uk_inspection_card）。
 */
@Repository
public class InspectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public InspectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 检验记录行。 */
    public record InspectionRow(
            long id,
            long cardId,
            String selfInspector,
            String mutualInspector,
            String requestId,
            Instant createdAt) {
    }

    public void insert(long cardId, String selfInspector, String mutualInspector, String requestId, Instant at) {
        jdbcTemplate.update(
                "INSERT INTO inspection_record (card_id, self_inspector, mutual_inspector, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                cardId, selfInspector, mutualInspector, requestId, Timestamp.from(at));
    }

    public InspectionRow findByCard(long cardId) {
        List<InspectionRow> rows = jdbcTemplate.query(
                "SELECT id, card_id, self_inspector, mutual_inspector, request_id, created_at "
                        + "FROM inspection_record WHERE card_id = ?",
                (rs, n) -> new InspectionRow(
                        rs.getLong("id"), rs.getLong("card_id"), rs.getString("self_inspector"),
                        rs.getString("mutual_inspector"), rs.getString("request_id"),
                        rs.getTimestamp("created_at").toInstant()),
                cardId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
