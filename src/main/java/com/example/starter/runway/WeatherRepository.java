package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 气象读数数据访问：weather_reading 表（合成数据）。
 */
@Repository
public class WeatherRepository {

    private static final RowMapper<WeatherReading> MAPPER = (rs, rowNum) -> new WeatherReading(
            rs.getLong("id"),
            rs.getString("runway_id"),
            rs.getInt("rvr_meters"),
            rs.getTimestamp("recorded_at").toInstant());

    private final JdbcTemplate jdbc;

    public WeatherRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public WeatherReading insert(WeatherReading reading) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO weather_reading (runway_id, rvr_meters, recorded_at) VALUES (?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, reading.runwayId());
            ps.setInt(2, reading.rvrMeters());
            ps.setTimestamp(3, Timestamp.from(reading.recordedAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return new WeatherReading(id, reading.runwayId(), reading.rvrMeters(), reading.recordedAt());
    }

    /**
     * 查询指定时刻或之前最近的一条读数，用于巡检时刻的低能见度判定。
     */
    public Optional<WeatherReading> findLatestAtOrBefore(String runwayId, Instant at) {
        List<WeatherReading> rows = jdbc.query(
                "SELECT * FROM weather_reading WHERE runway_id = ? AND recorded_at <= ?"
                        + " ORDER BY recorded_at DESC, id DESC LIMIT 1",
                MAPPER, runwayId, Timestamp.from(at));
        return rows.stream().findFirst();
    }

    public List<WeatherReading> findByRunwayId(String runwayId) {
        return jdbc.query("SELECT * FROM weather_reading WHERE runway_id = ? ORDER BY recorded_at, id",
                MAPPER, runwayId);
    }
}
