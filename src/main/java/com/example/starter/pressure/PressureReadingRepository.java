package com.example.starter.pressure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 压力检测读数仓储：封存后迟到数据不落本仓（仅写迟到事件）。
 */
@Repository
public class PressureReadingRepository {

    private final JdbcTemplate jdbc;

    public PressureReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long stationId, String requestId, LocalDateTime detectedAt,
                       BigDecimal pressureMpa, String grade, Long taskId) {
        jdbc.update("""
                        INSERT INTO pressure_reading
                            (station_id, request_id, detected_at, pressure_mpa, grade, task_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                stationId, requestId, detectedAt, pressureMpa, grade, taskId);
    }
}
