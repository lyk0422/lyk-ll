package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 人工复核台账与归档后迟到温度事件数据访问。
 */
@Repository
public class MiscEventRepository {

    private final JdbcTemplate jdbc;

    public MiscEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertManualReview(long stationId, long unitId, String requestId, int adjustmentCount,
                                   Instant windowStart, Instant windowEnd, String reason, Instant now) {
        jdbc.update("INSERT INTO manual_review "
                        + "(station_id, unit_id, request_id, adjustment_count, window_start, window_end, reason, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                stationId, unitId, requestId, adjustmentCount,
                Timestamp.from(windowStart), Timestamp.from(windowEnd), reason, Timestamp.from(now));
    }

    public long countManualReviewByRequest(String requestId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manual_review WHERE request_id = ?", Long.class, requestId);
        return count == null ? 0 : count;
    }

    public long countManualReviewByUnit(long unitId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM manual_review WHERE unit_id = ?", Long.class, unitId);
        return count == null ? 0 : count;
    }

    public void insertLateEvent(long stationId, Long unitId, String requestId, String detailJson,
                                Instant observedAt, Instant receivedAt, Instant now) {
        jdbc.update("INSERT INTO late_temperature_event "
                        + "(station_id, unit_id, request_id, detail_json, observed_at, received_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                stationId, unitId, requestId, detailJson,
                Timestamp.from(observedAt), Timestamp.from(receivedAt), Timestamp.from(now));
    }

    public long countLateEventsByStation(long stationId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_temperature_event WHERE station_id = ?", Long.class, stationId);
        return count == null ? 0 : count;
    }
}
