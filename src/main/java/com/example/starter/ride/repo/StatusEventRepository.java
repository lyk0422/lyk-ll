package com.example.starter.ride.repo;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 启停链事件仓储：所有状态迁移均追加不可变事件，支持复算。
 */
@Repository
public class StatusEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public StatusEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void append(String rideId, LocalDate bizDate, String eventType,
                       String requestId, String detail, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO ride_status_event (ride_id, biz_date, event_type, request_id, detail, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                rideId, bizDate, eventType, requestId, detail, java.sql.Timestamp.from(createdAt));
    }

    public long countByType(String rideId, LocalDate bizDate, String eventType) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ride_status_event WHERE ride_id = ? AND biz_date = ? AND event_type = ?",
                Long.class, rideId, bizDate, eventType);
        return count == null ? 0L : count;
    }

    /**
     * 按时间顺序读取当日全部启停链事件，供复算。
     */
    public List<EventRow> findByRideAndDate(String rideId, LocalDate bizDate) {
        return jdbcTemplate.query(
                "SELECT id, event_type, request_id, detail, created_at FROM ride_status_event "
                        + "WHERE ride_id = ? AND biz_date = ? ORDER BY id",
                (rs, rowNum) -> new EventRow(
                        rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("request_id"), rs.getString("detail"),
                        rs.getTimestamp("created_at").toInstant()),
                rideId, bizDate);
    }

    public record EventRow(long id, String eventType, String requestId, String detail, Instant createdAt) {
    }
}
