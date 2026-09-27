package com.example.starter.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 历史水下记录更正差异访问。仅独立写入差异行：不回写原 dive_record、
 * 不动 bottom_time_ledger 与余量积分账户，已关闭潜次语义不变。
 */
@Repository
public class RecordCorrectionRepository {

    private static final RowMapper<RecordCorrectionRow> MAPPER = (rs, n) -> new RecordCorrectionRow(
            rs.getLong("id"),
            rs.getString("dive_request_id"),
            rs.getLong("target_record_id"),
            rs.getInt("plan_version"),
            rs.getString("diver_code"),
            rs.getString("phase"),
            rs.getInt("recorded_duration_min"),
            rs.getInt("corrected_duration_min"),
            rs.getInt("delta_min"),
            rs.getString("reason"),
            rs.getInt("version"),
            rs.getTimestamp("corrected_at_utc").toInstant(),
            rs.getTimestamp("created_at_utc").toInstant());

    private final JdbcTemplate jdbc;

    public RecordCorrectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(RecordCorrectionRow row) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dive_record_correction "
                            + "(dive_request_id, target_record_id, plan_version, diver_code, phase, "
                            + "recorded_duration_min, corrected_duration_min, delta_min, reason, "
                            + "version, corrected_at_utc, created_at_utc) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.diveRequestId());
            ps.setLong(2, row.targetRecordId());
            ps.setInt(3, row.planVersion());
            ps.setString(4, row.diverCode());
            ps.setString(5, row.phase());
            ps.setInt(6, row.recordedDurationMin());
            ps.setInt(7, row.correctedDurationMin());
            ps.setInt(8, row.deltaMin());
            ps.setString(9, row.reason());
            ps.setInt(10, row.version());
            ps.setTimestamp(11, Timestamp.from(row.correctedAtUtc()));
            ps.setTimestamp(12, Timestamp.from(row.createdAtUtc()));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? -1L : key.longValue();
    }

    /** 某潜次现有更正条数（即当前更正版本号；首条前为 0）。 */
    public int countByDive(String diveRequestId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM dive_record_correction WHERE dive_request_id = ?",
                Integer.class, diveRequestId);
        return count == null ? 0 : count;
    }

    public List<RecordCorrectionRow> findByDive(String diveRequestId) {
        return jdbc.query("SELECT * FROM dive_record_correction WHERE dive_request_id = ? "
                        + "ORDER BY version",
                MAPPER, diveRequestId);
    }
}
