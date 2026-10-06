package com.example.starter.weight.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 结案后迟到非现场数据事件仓储：只记迟到事件，不改已结案件。
 */
@Repository
public class OffsiteLateEventRepository {

    private final JdbcTemplate jdbc;

    public OffsiteLateEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录迟到非现场数据事件。
     */
    public void insert(long offsiteRecordId, long caseId, String plateNo, String laneCode,
                       Instant weighTime, BigDecimal totalKg, String note, Instant createdAt) {
        jdbc.update("""
                INSERT INTO offsite_late_event
                  (offsite_record_id, case_id, plate_no, lane_code, weigh_time, total_kg, note, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, offsiteRecordId, caseId, plateNo, laneCode, Timestamp.from(weighTime),
                totalKg, note, Timestamp.from(createdAt));
    }

    /**
     * 按案件查询全部迟到非现场事件（备查）。
     */
    public List<OffsiteLateEvent> findByCaseId(long caseId) {
        return jdbc.query("""
                SELECT offsite_record_id, plate_no, lane_code, weigh_time, total_kg, note, created_at
                FROM offsite_late_event WHERE case_id = ? ORDER BY id
                """,
                (rs, n) -> new OffsiteLateEvent(
                        rs.getLong("offsite_record_id"),
                        rs.getString("plate_no"),
                        rs.getString("lane_code"),
                        rs.getTimestamp("weigh_time").toInstant(),
                        rs.getBigDecimal("total_kg"),
                        rs.getString("note"),
                        rs.getTimestamp("created_at").toInstant()),
                caseId);
    }

    /**
     * 迟到非现场事件视图（备查）。
     */
    public record OffsiteLateEvent(
            long offsiteRecordId,
            String plateNo,
            String laneCode,
            Instant weighTime,
            BigDecimal totalKg,
            String note,
            Instant createdAt) {
    }
}
