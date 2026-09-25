package com.example.starter.flood.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 降雨/水位读数仓储：唯一索引保证“测点+采样时刻”去重；归档后读数转迟到事件表。
 */
@Repository
public class ReadingRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReadingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入降雨读数；重复键由唯一索引拒绝。
     *
     * @return true=新写入；false=重复读数
     */
    public boolean insertRainfall(Long pondId, String stationCode, LocalDateTime sampleTime,
                                  BigDecimal rainfallDepth, int durationMinutes,
                                  LocalDateTime recordedAt) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO rainfall_reading(pond_id, station_code, sample_time, "
                            + "rainfall_depth, duration_minutes, recorded_at) VALUES (?,?,?,?,?,?)",
                    pondId, stationCode, Timestamp.valueOf(sampleTime),
                    rainfallDepth, durationMinutes, Timestamp.valueOf(recordedAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 插入水位/监测读数；重复键由唯一索引拒绝。
     *
     * @return true=新写入；false=重复读数
     */
    public boolean insertWater(Long pondId, String stationCode, LocalDateTime sampleTime,
                               BigDecimal waterLevel, BigDecimal dryBeachLength,
                               BigDecimal phreaticDepth, LocalDateTime recordedAt) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO water_reading(pond_id, station_code, sample_time, water_level, "
                            + "dry_beach_length, phreatic_depth, recorded_at) VALUES (?,?,?,?,?,?,?)",
                    pondId, stationCode, Timestamp.valueOf(sampleTime), waterLevel,
                    dryBeachLength, phreaticDepth, Timestamp.valueOf(recordedAt));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 登记迟到读数事件（处置链归档后送达，不入读数表）。
     */
    public void insertLateEvent(Long pondId, String readingType, String stationCode,
                                LocalDateTime sampleTime, LocalDateTime recordedAt, String payload) {
        jdbcTemplate.update(
                "INSERT INTO late_reading_event(pond_id, reading_type, station_code, "
                        + "sample_time, recorded_at, payload) VALUES (?,?,?,?,?,?)",
                pondId, readingType, stationCode, Timestamp.valueOf(sampleTime),
                Timestamp.valueOf(recordedAt), payload);
    }

    public int countLateEvents(Long pondId, String readingType, String stationCode,
                               LocalDateTime sampleTime) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM late_reading_event WHERE pond_id = ? AND reading_type = ? "
                        + "AND station_code = ? AND sample_time = ?",
                Integer.class, pondId, readingType, stationCode, Timestamp.valueOf(sampleTime));
        return count == null ? 0 : count;
    }

    /**
     * 测试辅助：统计某库降雨读数条数。
     */
    public int countRainfall(Long pondId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM rainfall_reading WHERE pond_id = ?", Integer.class, pondId);
        return count == null ? 0 : count;
    }

    /**
     * 测试辅助：统计某库水位读数条数。
     */
    public int countWater(Long pondId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM water_reading WHERE pond_id = ?", Integer.class, pondId);
        return count == null ? 0 : count;
    }
}
