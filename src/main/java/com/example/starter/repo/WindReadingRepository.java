package com.example.starter.repo;

import com.example.starter.model.WindReading;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 风速监测数据访问，按站点与时刻去重。
 */
@Repository
public class WindReadingRepository {

    private static final RowMapper<WindReading> MAPPER = (rs, n) -> new WindReading(
            rs.getLong("id"), rs.getLong("line_id"), rs.getLong("station_id"),
            rs.getTimestamp("measured_at").toInstant(), rs.getDouble("speed_ms"), rs.getBoolean("late"));

    private final JdbcTemplate jdbc;

    public WindReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条风速数据，返回生成主键。
     */
    public long insert(long lineId, long stationId, Instant measuredAt, double speedMs, boolean late) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO wind_reading (line_id, station_id, measured_at, speed_ms, late) "
                            + "VALUES (?, ?, ?, ?, ?)", new String[]{"id"});
            ps.setLong(1, lineId);
            ps.setLong(2, stationId);
            ps.setTimestamp(3, Timestamp.from(measuredAt));
            ps.setDouble(4, speedMs);
            ps.setBoolean(5, late);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /**
     * 按站点与时刻查询（去重判定用）。
     */
    public Optional<WindReading> findByStationAndTime(long stationId, Instant measuredAt) {
        return jdbc.query(
                        "SELECT id, line_id, station_id, measured_at, speed_ms, late FROM wind_reading "
                                + "WHERE station_id = ? AND measured_at = ?",
                        MAPPER, stationId, Timestamp.from(measuredAt))
                .stream().findFirst();
    }

    /**
     * 站点已入库的最大测量时刻，无数据返回 null。
     */
    public Instant maxMeasuredAt(long stationId) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MAX(measured_at) FROM wind_reading WHERE station_id = ?",
                Timestamp.class, stationId);
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 窗口内站点最小测量时刻（判定窗口是否恰满时长），无数据返回 null。
     */
    public Instant minMeasuredAtInWindow(long stationId, Instant fromInclusive, Instant toInclusive) {
        Timestamp ts = jdbc.queryForObject(
                "SELECT MIN(measured_at) FROM wind_reading "
                        + "WHERE station_id = ? AND measured_at >= ? AND measured_at <= ?",
                Timestamp.class, stationId, Timestamp.from(fromInclusive), Timestamp.from(toInclusive));
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 窗口内两个站点全部数据的平均风速，无数据返回 null。
     */
    public Double avgSpeedInWindow(long stationA, long stationB, Instant fromInclusive, Instant toInclusive) {
        return jdbc.queryForObject(
                "SELECT AVG(speed_ms) FROM wind_reading "
                        + "WHERE station_id IN (?, ?) AND measured_at >= ? AND measured_at <= ?",
                Double.class, stationA, stationB,
                Timestamp.from(fromInclusive), Timestamp.from(toInclusive));
    }

    /**
     * 窗口内站点风速不低于阈值的数据条数（复开条件判定用）。
     */
    public int countAtOrAbove(long stationId, Instant fromInclusive, Instant toInclusive, double thresholdMs) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wind_reading "
                        + "WHERE station_id = ? AND measured_at >= ? AND measured_at <= ? AND speed_ms >= ?",
                Integer.class, stationId, Timestamp.from(fromInclusive), Timestamp.from(toInclusive), thresholdMs);
        return count == null ? 0 : count;
    }
}
