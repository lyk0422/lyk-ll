package com.example.starter.repo;

import com.example.starter.po.TemperatureReading;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 温度采集数据访问。
 */
@Repository
public class TemperatureRepository {

    private final JdbcTemplate jdbc;

    public TemperatureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<TemperatureReading> MAPPER = (rs, n) -> {
        TemperatureReading r = new TemperatureReading();
        r.setId(rs.getLong("id"));
        r.setUnitId(rs.getLong("unit_id"));
        r.setStationId(rs.getLong("station_id"));
        r.setSupplyTemp(rs.getBigDecimal("supply_temp"));
        r.setReturnTemp(rs.getBigDecimal("return_temp"));
        r.setObservedAt(rs.getTimestamp("observed_at").toInstant());
        r.setReceivedAt(rs.getTimestamp("received_at").toInstant());
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return r;
    };

    public void insert(long unitId, long stationId, BigDecimal supplyTemp, BigDecimal returnTemp,
                       Instant observedAt, Instant receivedAt, Instant now) {
        jdbc.update("INSERT INTO temperature_reading "
                        + "(unit_id, station_id, supply_temp, return_temp, observed_at, received_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                unitId, stationId, supplyTemp, returnTemp,
                Timestamp.from(observedAt), Timestamp.from(receivedAt), Timestamp.from(now));
    }

    public long countByStation(long stationId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM temperature_reading WHERE station_id = ?", Long.class, stationId);
        return count == null ? 0 : count;
    }

    /**
     * 取换热站下各单元“评估时刻之前（含）的最新一条”回水温度。
     * 最新按 observed_at 降序、同刻按 id 降序确定。返回每行：unit_id, return_temp。
     */
    public List<java.util.Map<String, Object>> latestReturnTemps(long stationId, Instant asOf) {
        return jdbc.queryForList(
                "SELECT unit_id, return_temp FROM ("
                        + "  SELECT unit_id, return_temp, "
                        + "  ROW_NUMBER() OVER (PARTITION BY unit_id ORDER BY observed_at DESC, id DESC) AS rn "
                        + "  FROM temperature_reading WHERE station_id = ? AND observed_at <= ?"
                        + ") ranked WHERE rn = 1",
                stationId, Timestamp.from(asOf));
    }

    public List<TemperatureReading> findByStation(long stationId) {
        return jdbc.query("SELECT id, unit_id, station_id, supply_temp, return_temp, observed_at, received_at, created_at "
                + "FROM temperature_reading WHERE station_id = ? ORDER BY observed_at, id", MAPPER, stationId);
    }
}
