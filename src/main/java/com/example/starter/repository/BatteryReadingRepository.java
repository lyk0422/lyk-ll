package com.example.starter.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.model.BatteryReading;

/**
 * 电池容量读数数据访问；按“基站＋抄表时刻”去重，乱序按抄表时刻归位。
 */
@Repository
public class BatteryReadingRepository {

    private final JdbcTemplate jdbcTemplate;

    public BatteryReadingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 写入读数，返回自增主键；唯一约束 (station_code, reading_time) 由数据库兜底。
     */
    public long insert(String stationCode, Instant readingTime, BigDecimal remainingPercent,
                       String requestId, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO battery_reading (station_code, reading_time, remaining_percent, request_id, created_at)"
                        + " VALUES (?,?,?,?,?)",
                stationCode, StationRepository.toOffsetDateTime(readingTime), remainingPercent,
                requestId, StationRepository.toOffsetDateTime(now));
        Long id = jdbcTemplate.queryForObject(
                "SELECT MAX(id) FROM battery_reading WHERE station_code = ?", Long.class, stationCode);
        return id == null ? -1L : id;
    }

    /**
     * 按“基站＋抄表时刻”查询已有读数，用于去重。
     */
    public Optional<BatteryReading> findByStationAndTime(String stationCode, Instant readingTime) {
        return jdbcTemplate.query(
                        "SELECT * FROM battery_reading WHERE station_code = ? AND reading_time = ?",
                        this::map, stationCode, StationRepository.toOffsetDateTime(readingTime))
                .stream().findFirst();
    }

    /**
     * 查询指定时刻（含）之前最近一次抄表读数，乱序到达的读数按抄表时刻归位后参与排序。
     */
    public Optional<BatteryReading> findLatestAtOrBefore(String stationCode, Instant time) {
        return jdbcTemplate.query(
                        "SELECT * FROM battery_reading WHERE station_code = ? AND reading_time <= ?"
                                + " ORDER BY reading_time DESC LIMIT 1",
                        this::map, stationCode, StationRepository.toOffsetDateTime(time))
                .stream().findFirst();
    }

    private BatteryReading map(ResultSet rs, int rowNum) throws SQLException {
        return new BatteryReading(rs.getLong("id"), rs.getString("station_code"),
                StationRepository.toInstant(rs, "reading_time"), rs.getBigDecimal("remaining_percent"),
                rs.getString("request_id"), StationRepository.toInstant(rs, "created_at"));
    }
}
