package com.example.starter.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 温湿度读数与迟到事件数据访问。
 */
@Repository
public class ReadingRepository {

    private static final RowMapper<ReadingRow> MAPPER = (rs, n) -> new ReadingRow(
            rs.getString("point_code"),
            (Double) rs.getObject("temperature"),
            (Double) rs.getObject("humidity"),
            rs.getTimestamp("measured_at").toLocalDateTime(),
            rs.getString("phase")
    );

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertReading(long siloId, String pointCode, Double temperature, Double humidity,
                              LocalDateTime measuredAt, String phase) {
        jdbc.update("INSERT INTO sensor_reading (silo_id, point_code, temperature, humidity, measured_at, "
                        + "phase, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                siloId, pointCode, temperature, humidity, Timestamp.valueOf(measuredAt), phase,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public void insertLateEvent(long siloId, String pointCode, Double temperature, Double humidity,
                                LocalDateTime measuredAt) {
        jdbc.update("INSERT INTO late_reading_event (silo_id, point_code, temperature, humidity, measured_at, "
                        + "received_at) VALUES (?, ?, ?, ?, ?, ?)",
                siloId, pointCode, temperature, humidity,
                measuredAt == null ? null : Timestamp.valueOf(measuredAt),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public long countLateEvents(long siloId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_reading_event WHERE silo_id = ?", Long.class, siloId);
        return count == null ? 0L : count;
    }

    /**
     * 温度曲线（可复算）：按采样时刻、测点排序。
     */
    public List<ReadingRow> findCurve(long siloId) {
        return jdbc.query("SELECT point_code, temperature, humidity, measured_at, phase FROM sensor_reading "
                + "WHERE silo_id = ? ORDER BY measured_at, id", MAPPER, siloId);
    }

    /**
     * 读数行。
     */
    public record ReadingRow(String pointCode, Double temperature, Double humidity, LocalDateTime measuredAt,
                             String phase) {
    }
}
