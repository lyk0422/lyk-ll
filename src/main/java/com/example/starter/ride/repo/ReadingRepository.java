package com.example.starter.ride.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 运行读数仓储。
 */
@Repository
public class ReadingRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReadingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String rideId, LocalDate bizDate, double loadKg, double speedMps,
                       boolean triggeredSlowStop, boolean lateAfterArchive, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO operation_reading (ride_id, biz_date, load_value, speed_value, "
                        + "triggered_slow_stop, late_after_archive, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                rideId, bizDate, loadKg, speedMps, triggeredSlowStop, lateAfterArchive,
                Timestamp.from(createdAt));
    }
}
