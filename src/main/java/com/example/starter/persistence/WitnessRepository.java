package com.example.starter.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.WitnessStatus;

/**
 * 见证链 JDBC 访问：按 seq 追加，可整链复算。
 */
@Repository
public class WitnessRepository {

    private final JdbcTemplate jdbcTemplate;

    public WitnessRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 见证链记录行。 */
    public record WitnessRow(
            long id,
            long cardId,
            int seq,
            WitnessStatus status,
            String witnessName,
            Instant eventTime,
            String requestId,
            String note) {
    }

    public void append(long cardId, int seq, WitnessStatus status, String witnessName, Instant eventTime,
                       String requestId, String note) {
        jdbcTemplate.update(
                "INSERT INTO witness_record (card_id, seq, status, witness_name, event_time, request_id, note, "
                        + "created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                cardId, seq, status.name(), witnessName, Timestamp.from(eventTime), requestId, note,
                Timestamp.from(eventTime));
    }

    public int countByCard(long cardId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM witness_record WHERE card_id = ?", Integer.class, cardId);
        return count == null ? 0 : count;
    }

    public List<WitnessRow> listByCard(long cardId) {
        return jdbcTemplate.query(
                "SELECT id, card_id, seq, status, witness_name, event_time, request_id, note "
                        + "FROM witness_record WHERE card_id = ? ORDER BY seq",
                (rs, n) -> new WitnessRow(
                        rs.getLong("id"), rs.getLong("card_id"), rs.getInt("seq"),
                        WitnessStatus.valueOf(rs.getString("status")),
                        rs.getString("witness_name"),
                        rs.getTimestamp("event_time").toInstant(),
                        rs.getString("request_id"), rs.getString("note")),
                cardId);
    }

    /** 取链上最新一条。 */
    public WitnessRow findLatest(long cardId) {
        List<WitnessRow> rows = jdbcTemplate.query(
                "SELECT id, card_id, seq, status, witness_name, event_time, request_id, note "
                        + "FROM witness_record WHERE card_id = ? ORDER BY seq DESC LIMIT 1",
                (rs, n) -> new WitnessRow(
                        rs.getLong("id"), rs.getLong("card_id"), rs.getInt("seq"),
                        WitnessStatus.valueOf(rs.getString("status")),
                        rs.getString("witness_name"),
                        rs.getTimestamp("event_time").toInstant(),
                        rs.getString("request_id"), rs.getString("note")),
                cardId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
