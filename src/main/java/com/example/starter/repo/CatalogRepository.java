package com.example.starter.repo;

import com.example.starter.po.HeatStation;
import com.example.starter.po.HeatingUnit;
import com.example.starter.po.Valve;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 换热站、单元、阀门基础数据访问。
 */
@Repository
public class CatalogRepository {

    private final JdbcTemplate jdbc;

    public CatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<HeatStation> STATION_MAPPER = (rs, n) -> {
        HeatStation s = new HeatStation();
        s.setId(rs.getLong("id"));
        s.setStationCode(rs.getString("station_code"));
        s.setStationName(rs.getString("station_name"));
        s.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return s;
    };

    private static final RowMapper<HeatingUnit> UNIT_MAPPER = (rs, n) -> {
        HeatingUnit u = new HeatingUnit();
        u.setId(rs.getLong("id"));
        u.setStationId(rs.getLong("station_id"));
        u.setUnitCode(rs.getString("unit_code"));
        u.setUnitName(rs.getString("unit_name"));
        u.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return u;
    };

    private static final RowMapper<Valve> VALVE_MAPPER = (rs, n) -> {
        Valve v = new Valve();
        v.setId(rs.getLong("id"));
        v.setUnitId(rs.getLong("unit_id"));
        v.setValveCode(rs.getString("valve_code"));
        v.setOpening(rs.getBigDecimal("opening"));
        v.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return v;
    };

    public HeatStation findStationByCode(String stationCode) {
        List<HeatStation> list = jdbc.query(
                "SELECT id, station_code, station_name, created_at FROM heat_station WHERE station_code = ?",
                STATION_MAPPER, stationCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<HeatingUnit> findUnitsByStation(long stationId) {
        return jdbc.query(
                "SELECT id, station_id, unit_code, unit_name, created_at FROM heating_unit WHERE station_id = ? ORDER BY id",
                UNIT_MAPPER, stationId);
    }

    public HeatingUnit findUnit(long stationId, String unitCode) {
        List<HeatingUnit> list = jdbc.query(
                "SELECT id, station_id, unit_code, unit_name, created_at FROM heating_unit WHERE station_id = ? AND unit_code = ?",
                UNIT_MAPPER, stationId, unitCode);
        return list.isEmpty() ? null : list.get(0);
    }

    public Valve findValveByUnit(long unitId) {
        List<Valve> list = jdbc.query(
                "SELECT id, unit_id, valve_code, opening, updated_at FROM valve WHERE unit_id = ?",
                VALVE_MAPPER, unitId);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<Valve> findValvesByStation(long stationId) {
        return jdbc.query(
                "SELECT v.id, v.unit_id, v.valve_code, v.opening, v.updated_at FROM valve v "
                        + "JOIN heating_unit u ON u.id = v.unit_id WHERE u.station_id = ? ORDER BY v.id",
                VALVE_MAPPER, stationId);
    }

    /**
     * 行级锁定读取阀门，供步进事务使用。
     */
    public Valve findValveByUnitForUpdate(long unitId) {
        List<Valve> list = jdbc.query(
                "SELECT id, unit_id, valve_code, opening, updated_at FROM valve WHERE unit_id = ? FOR UPDATE",
                VALVE_MAPPER, unitId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void updateValveOpening(long valveId, BigDecimal opening, Instant updatedAt) {
        jdbc.update("UPDATE valve SET opening = ?, updated_at = ? WHERE id = ?",
                opening, Timestamp.from(updatedAt), valveId);
    }

    public long createStation(String stationCode, String stationName, Instant now) {
        jdbc.update("INSERT INTO heat_station (station_code, station_name, created_at) VALUES (?, ?, ?)",
                stationCode, stationName, Timestamp.from(now));
        return jdbc.queryForObject("SELECT id FROM heat_station WHERE station_code = ?",
                Long.class, stationCode);
    }

    public long createUnit(long stationId, String unitCode, String unitName, Instant now) {
        jdbc.update("INSERT INTO heating_unit (station_id, unit_code, unit_name, created_at) VALUES (?, ?, ?, ?)",
                stationId, unitCode, unitName, Timestamp.from(now));
        return jdbc.queryForObject(
                "SELECT id FROM heating_unit WHERE station_id = ? AND unit_code = ?",
                Long.class, stationId, unitCode);
    }

    public void createValve(long unitId, String valveCode, BigDecimal opening, Instant now) {
        jdbc.update("INSERT INTO valve (unit_id, valve_code, opening, updated_at) VALUES (?, ?, ?, ?)",
                unitId, valveCode, opening, Timestamp.from(now));
    }
}
