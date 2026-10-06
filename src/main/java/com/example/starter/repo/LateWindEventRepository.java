package com.example.starter.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 迟到风速事件数据访问。
 */
@Repository
public class LateWindEventRepository {

    private final JdbcTemplate jdbc;

    public LateWindEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long lineId, long segmentId, LocalDateTime measuredAt, BigDecimal speed,
                       LocalDateTime receivedAt, String reason) {
        jdbc.update("INSERT INTO late_wind_event (line_id, segment_id, measured_at, speed, received_at, reason) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                lineId, segmentId, Timestamp.valueOf(measuredAt), speed, Timestamp.valueOf(receivedAt), reason);
    }

    public long countByLineId(long lineId) {
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM late_wind_event WHERE line_id = ?",
                Long.class, lineId);
        return cnt == null ? 0 : cnt;
    }
}
