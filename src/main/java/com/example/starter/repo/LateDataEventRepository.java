package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 迟到风速数据事件数据访问。
 */
@Repository
public class LateDataEventRepository {

    private final JdbcTemplate jdbc;

    public LateDataEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一条迟到数据事件。
     */
    public void insert(long lineId, long stationId, Instant measuredAt, double speedMs, String reason) {
        jdbc.update("INSERT INTO late_data_event (line_id, station_id, measured_at, speed_ms, reason) "
                        + "VALUES (?, ?, ?, ?, ?)",
                lineId, stationId, Timestamp.from(measuredAt), speedMs, reason);
    }

    /**
     * 线路迟到事件条数（测试核验用）。
     */
    public int countByLine(long lineId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_data_event WHERE line_id = ?", Integer.class, lineId);
        return count == null ? 0 : count;
    }
}
