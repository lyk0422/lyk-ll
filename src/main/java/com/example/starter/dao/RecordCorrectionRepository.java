package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 历史水下记录更正差异访问：独立留存，不回写已关闭潜次与积分账。
 */
@Repository
public class RecordCorrectionRepository {

    private static final RowMapper<RecordCorrectionRow> MAPPER = (rs, n) -> new RecordCorrectionRow(
            rs.getLong("id"),
            rs.getString("dive_request_id"),
            rs.getString("diver_code"),
            rs.getInt("old_bottom_min"),
            rs.getInt("new_bottom_min"),
            rs.getInt("delta_min"),
            rs.getString("request_id"),
            rs.getTimestamp("corrected_at_utc").toInstant(),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public RecordCorrectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RecordCorrectionRow row) {
        jdbc.update("INSERT INTO dive_record_correction "
                        + "(dive_request_id, diver_code, old_bottom_min, new_bottom_min, delta_min, "
                        + "request_id, corrected_at_utc, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.diveRequestId(), row.diverCode(), row.oldBottomMin(), row.newBottomMin(),
                row.deltaMin(), row.requestId(), Timestamp.from(row.correctedAtUtc()),
                Timestamp.from(row.createdAtUtc()));
    }

    public List<RecordCorrectionRow> findByDive(String diveRequestId) {
        return jdbc.query("SELECT * FROM dive_record_correction WHERE dive_request_id = ? ORDER BY id",
                MAPPER, diveRequestId);
    }
}
