package com.example.starter.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 传感器读数数据访问。读数按“分区＋采样时刻”唯一约束去重，乱序上报依靠唯一键与按时刻排序归位。
 */
@Repository
public class ReadingRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReadingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Reading(
            Long id,
            String zoneCode,
            Instant sampleTime,
            BigDecimal temperature,
            BigDecimal humidity) {
    }

    private static final RowMapper<Reading> MAPPER = (rs, n) -> new Reading(
            rs.getLong("id"),
            rs.getString("zone_code"),
            rs.getTimestamp("sample_time").toInstant(),
            rs.getBigDecimal("temperature"),
            rs.getBigDecimal("humidity"));

    /**
     * 插入读数；同分区同时刻重复上报抛唯一键冲突，由上层按幂等去重处理。
     */
    public void insert(String zoneCode, Instant sampleTime, BigDecimal temperature,
                       BigDecimal humidity, Instant ingestedAt) {
        jdbcTemplate.update("INSERT INTO sensor_reading (zone_code, sample_time, temperature, humidity, ingested_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                zoneCode, Timestamp.from(sampleTime), temperature, humidity, Timestamp.from(ingestedAt));
    }

    public boolean isDuplicateKey(Exception e) {
        return e instanceof DuplicateKeyException;
    }

    /**
     * 该分区最新一个采样点的时刻。
     */
    public Instant findLatestSampleTime(String zoneCode) {
        Timestamp ts = jdbcTemplate.queryForObject(
                "SELECT MAX(sample_time) FROM sensor_reading WHERE zone_code = ?",
                Timestamp.class, zoneCode);
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 返回该分区截至 {@code until}（含）的最近 {@code limit} 个读数，按采样时刻升序，供连续偏离检测。
     */
    public List<Reading> findRecentUntil(String zoneCode, Instant until, int limit) {
        return jdbcTemplate.query("SELECT id, zone_code, sample_time, temperature, humidity FROM ("
                        + "SELECT id, zone_code, sample_time, temperature, humidity FROM sensor_reading "
                        + "WHERE zone_code = ? AND sample_time <= ? ORDER BY sample_time DESC LIMIT ?"
                        + ") t ORDER BY sample_time ASC",
                MAPPER, zoneCode, Timestamp.from(until), limit);
    }

    /**
     * UTC 日界内的全部读数（按时刻升序），供日评估复算。
     */
    public List<Reading> findByUtcDate(String zoneCode, java.time.LocalDate utcDate) {
        return jdbcTemplate.query("SELECT id, zone_code, sample_time, temperature, humidity FROM sensor_reading "
                        + "WHERE zone_code = ? AND sample_time >= ? AND sample_time < ? ORDER BY sample_time ASC",
                MAPPER, zoneCode, Timestamp.from(utcDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
                Timestamp.from(utcDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
    }

    public int countByUtcDate(String zoneCode, java.time.LocalDate utcDate) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM sensor_reading WHERE zone_code = ? AND sample_time >= ? AND sample_time < ?",
                Integer.class, zoneCode,
                Timestamp.from(utcDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
                Timestamp.from(utcDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        return count == null ? 0 : count;
    }

    public void recordLate(String zoneCode, Instant sampleTime, BigDecimal temperature,
                           BigDecimal humidity, Instant recordedAt) {
        jdbcTemplate.update("INSERT INTO late_reading_event (zone_code, sample_time, temperature, humidity, recorded_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                zoneCode, Timestamp.from(sampleTime), temperature, humidity, Timestamp.from(recordedAt));
    }

    public int countLateByUtcDate(String zoneCode, java.time.LocalDate utcDate) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM late_reading_event WHERE zone_code = ? AND sample_time >= ? AND sample_time < ?",
                Integer.class, zoneCode,
                Timestamp.from(utcDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()),
                Timestamp.from(utcDate.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant()));
        return count == null ? 0 : count;
    }
}
