package com.example.starter.weight.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 结案后迟到过磅事件仓储：只记迟到事件并入证据备查，不改判已结案件。
 */
@Repository
public class LateWeighEventRepository {

    private final JdbcTemplate jdbc;

    public LateWeighEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录迟到过磅事件。
     */
    public void insert(long weighRecordId, long caseId, String plateNo, String stationCode,
                       Instant weighTime, String mode, BigDecimal totalKg, String note, Instant createdAt) {
        jdbc.update("""
                INSERT INTO late_weigh_event
                  (weigh_record_id, case_id, plate_no, station_code, weigh_time, mode, total_kg, note, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, weighRecordId, caseId, plateNo, stationCode, Timestamp.from(weighTime), mode,
                totalKg, note, Timestamp.from(createdAt));
    }

    /**
     * 按案件查询全部迟到事件（证据备查）。
     */
    public List<LateEvent> findByCaseId(long caseId) {
        return jdbc.query("""
                SELECT weigh_record_id, plate_no, station_code, weigh_time, mode, total_kg, note, created_at
                FROM late_weigh_event WHERE case_id = ? ORDER BY id
                """,
                (rs, n) -> new LateEvent(
                        rs.getLong("weigh_record_id"),
                        rs.getString("plate_no"),
                        rs.getString("station_code"),
                        rs.getTimestamp("weigh_time").toInstant(),
                        rs.getString("mode"),
                        rs.getBigDecimal("total_kg"),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()),
                caseId);
    }

    /**
     * 迟到事件视图（证据备查）。
     */
    public record LateEvent(
            long weighRecordId,
            String plateNo,
            String stationCode,
            Instant weighTime,
            String mode,
            BigDecimal totalKg,
            String note,
            Instant createdAt) {
    }
}
