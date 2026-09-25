package com.example.starter.repository;

import com.example.starter.domain.TemperatureReading;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 回水温度采集数据访问。
 */
@Repository
public class TemperatureRepository {

    private static final RowMapper<TemperatureReading> MAPPER = (rs, n) ->
            new TemperatureReading(rs.getLong("id"), rs.getLong("unit_id"),
                    rs.getBigDecimal("temperature"), rs.getTimestamp("recorded_at").toInstant(),
                    rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public TemperatureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入一条温度采集记录。
     */
    public void insert(long unitId, BigDecimal temperature, Instant recordedAt, Instant createdAt) {
        jdbc.update("INSERT INTO temperature_reading (unit_id, temperature, recorded_at, created_at)"
                        + " VALUES (?, ?, ?, ?)",
                unitId, temperature, Timestamp.from(recordedAt), Timestamp.from(createdAt));
    }

    /**
     * 查询换热站各单元在指定时间点（含）之前的最新一条温度记录。
     */
    public List<TemperatureReading> findLatestPerUnit(long stationId, Instant asOf) {
        return jdbc.query("SELECT t.id, t.unit_id, t.temperature, t.recorded_at, t.created_at"
                        + " FROM temperature_reading t"
                        + " JOIN heating_unit u ON u.id = t.unit_id"
                        + " JOIN (SELECT unit_id, MAX(recorded_at) AS mx FROM temperature_reading"
                        + "       WHERE recorded_at <= ? GROUP BY unit_id) m"
                        + "   ON t.unit_id = m.unit_id AND t.recorded_at = m.mx"
                        + " WHERE u.station_id = ? ORDER BY t.unit_id",
                MAPPER, Timestamp.from(asOf), stationId);
    }
}
