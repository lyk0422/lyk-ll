package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 历史水下记录更正台账访问。更正只追加独立差异，不回写已关闭潜次与积分账。
 */
@Repository
public class RecordCorrectionRepository {

    private static final RowMapper<RecordCorrectionRow> MAPPER = (rs, n) -> new RecordCorrectionRow(
            rs.getString("request_id"),
            rs.getString("dive_request_id"),
            rs.getString("diver_code"),
            rs.getString("phase"),
            rs.getInt("recorded_duration_min"),
            rs.getInt("corrected_duration_min"),
            rs.getInt("delta_min"),
            rs.getString("fingerprint"),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public RecordCorrectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RecordCorrectionRow row) {
        jdbc.update("INSERT INTO dive_record_correction "
                        + "(request_id, dive_request_id, diver_code, phase, recorded_duration_min, "
                        + "corrected_duration_min, delta_min, fingerprint, created_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.requestId(), row.diveRequestId(), row.diverCode(), row.phase(),
                row.recordedDurationMin(), row.correctedDurationMin(), row.deltaMin(),
                row.fingerprint(), Timestamp.from(row.createdAtUtc()));
    }

    public Optional<RecordCorrectionRow> find(String requestId) {
        return jdbc.query("SELECT * FROM dive_record_correction WHERE request_id = ?",
                MAPPER, requestId).stream().findFirst();
    }

    public List<RecordCorrectionRow> findByDive(String diveRequestId) {
        return jdbc.query("SELECT * FROM dive_record_correction WHERE dive_request_id = ? "
                        + "ORDER BY created_at_utc, request_id",
                MAPPER, diveRequestId);
    }
}
