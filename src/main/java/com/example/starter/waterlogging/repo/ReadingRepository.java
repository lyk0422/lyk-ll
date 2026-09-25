package com.example.starter.waterlogging.repo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.waterlogging.po.WaterLevelReading;

/**
 * 液位复测数据与归档后迟到事件数据访问。
 */
@Repository
public class ReadingRepository {

    private static final RowMapper<WaterLevelReading> MAPPER = (rs, n) -> new WaterLevelReading(
            rs.getLong("id"),
            rs.getString("station_code"),
            readTime(rs, "reading_time"),
            rs.getBigDecimal("level"),
            (Long) rs.getObject("plan_id"),
            readTime(rs, "recorded_at"));

    public ReadingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    protected final JdbcTemplate jdbcTemplate;

    private static LocalDateTime readTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    private Optional<WaterLevelReading> findExisting(String table, String stationCode, LocalDateTime readingTime) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT id, station_code, reading_time, level, plan_id, recorded_at FROM "
                            + table + " WHERE station_code = ? AND reading_time = ?",
                    MAPPER, stationCode, Timestamp.valueOf(readingTime)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private DedupResult insertInto(String table, WaterLevelReading reading) {
        Optional<WaterLevelReading> existing = findExisting(table, reading.stationCode(), reading.readingTime());
        if (existing.isPresent()) {
            return new DedupResult(existing.get(), false);
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement("""
                        INSERT INTO %s (station_code, reading_time, level, plan_id, recorded_at)
                        VALUES (?, ?, ?, ?, ?)
                        """.formatted(table), Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, reading.stationCode());
                ps.setTimestamp(2, Timestamp.valueOf(reading.readingTime()));
                ps.setBigDecimal(3, reading.level());
                if (reading.planId() == null) {
                    ps.setNull(4, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(4, reading.planId());
                }
                ps.setTimestamp(5, Timestamp.valueOf(reading.recordedAt()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            // 并发下唯一约束负方：读取既存记录，保持站点+时刻去重。
            return new DedupResult(findExisting(table, reading.stationCode(), reading.readingTime()).orElseThrow(),
                    false);
        }
        return new DedupResult(new WaterLevelReading(
                keyHolder.getKey().longValue(),
                reading.stationCode(),
                reading.readingTime(),
                reading.level(),
                reading.planId(),
                reading.recordedAt()), true);
    }

    /** 按站点+时刻去重写入预降期液位数据。 */
    public DedupResult insertIfAbsent(WaterLevelReading reading) {
        return insertInto("water_level_reading", reading);
    }

    /** 归档后迟到数据只登记迟到事件；重复时刻同样去重。 */
    public DedupResult insertLateIfAbsent(WaterLevelReading reading) {
        return insertInto("late_level_event", reading);
    }

    /** 不晚于给定时刻的最近一条液位数据时刻。 */
    public Optional<LocalDateTime> findLastReadingTime(String stationCode, LocalDateTime beforeOrAt) {
        List<LocalDateTime> times = jdbcTemplate.query("""
                        SELECT reading_time FROM water_level_reading
                        WHERE station_code = ? AND reading_time <= ?
                        ORDER BY reading_time DESC, id DESC LIMIT 1
                        """,
                (rs, n) -> readTime(rs, "reading_time"),
                stationCode, Timestamp.valueOf(beforeOrAt));
        return times.stream().findFirst();
    }

    /** 复算某预降单水位曲线。 */
    public List<WaterLevelReading> findByPlan(long planId) {
        return jdbcTemplate.query("""
                SELECT id, station_code, reading_time, level, plan_id, recorded_at
                FROM water_level_reading WHERE plan_id = ?
                ORDER BY station_code, reading_time, id
                """, MAPPER, planId);
    }

    /** 去重写入结果。 */
    public record DedupResult(WaterLevelReading reading, boolean inserted) {
    }
}
