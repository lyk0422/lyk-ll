package com.example.starter.schedule.repository;

import com.example.starter.schedule.domain.ContactWindow;
import com.example.starter.schedule.domain.GroundStation;
import com.example.starter.schedule.domain.Satellite;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 卫星、地面站、过境窗口等合成主数据访问。
 */
@Repository
public class MasterDataRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert satelliteInsert;
    private final SimpleJdbcInsert stationInsert;
    private final SimpleJdbcInsert windowInsert;

    public MasterDataRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.satelliteInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("satellite")
                .usingColumns("code", "name")
                .usingGeneratedKeyColumns("id");
        this.stationInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("ground_station")
                .usingColumns("code", "name")
                .usingGeneratedKeyColumns("id");
        this.windowInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("contact_window")
                .usingColumns("satellite_id", "station_id", "start_time", "end_time", "day_key")
                .usingGeneratedKeyColumns("id");
    }

    private static final RowMapper<Satellite> SATELLITE_MAPPER = (rs, n) ->
            new Satellite(rs.getLong("id"), rs.getString("code"),
                    rs.getString("name"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<GroundStation> STATION_MAPPER = (rs, n) ->
            new GroundStation(rs.getLong("id"), rs.getString("code"),
                    rs.getString("name"), rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<ContactWindow> WINDOW_MAPPER = (rs, n) ->
            new ContactWindow(rs.getLong("id"), rs.getLong("satellite_id"),
                    rs.getLong("station_id"),
                    rs.getTimestamp("start_time").toInstant(),
                    rs.getTimestamp("end_time").toInstant(),
                    rs.getString("day_key"));

    public Optional<Satellite> findSatelliteByCode(String code) {
        return jdbc.query("SELECT id, code, name, created_at FROM satellite WHERE code = ?",
                SATELLITE_MAPPER, code).stream().findFirst();
    }

    public Satellite getSatelliteByCode(String code) {
        return findSatelliteByCode(code).orElseThrow();
    }

    public Optional<Satellite> findSatelliteById(long satelliteId) {
        return jdbc.query("SELECT id, code, name, created_at FROM satellite WHERE id = ?",
                SATELLITE_MAPPER, satelliteId).stream().findFirst();
    }

    public Optional<GroundStation> findStationByCode(String code) {
        return jdbc.query("SELECT id, code, name, created_at FROM ground_station WHERE code = ?",
                STATION_MAPPER, code).stream().findFirst();
    }

    public List<GroundStation> findAllStations() {
        return jdbc.query("SELECT id, code, name, created_at FROM ground_station ORDER BY id",
                STATION_MAPPER);
    }

    public Optional<ContactWindow> findWindowById(long windowId) {
        return jdbc.query("""
                        SELECT id, satellite_id, station_id, start_time, end_time, day_key
                        FROM contact_window WHERE id = ?""",
                WINDOW_MAPPER, windowId).stream().findFirst();
    }

    public List<ContactWindow> findWindows(long stationId, long satelliteId, String dayKey) {
        return jdbc.query("""
                        SELECT id, satellite_id, station_id, start_time, end_time, day_key
                        FROM contact_window
                        WHERE station_id = ? AND satellite_id = ? AND day_key = ?
                        ORDER BY start_time""",
                WINDOW_MAPPER, stationId, satelliteId, dayKey);
    }

    /**
     * 查询某站某日全部预报窗口，按开始时刻升序。
     */
    public List<ContactWindow> findStationDayWindows(long stationId, String dayKey) {
        return jdbc.query("""
                        SELECT id, satellite_id, station_id, start_time, end_time, day_key
                        FROM contact_window
                        WHERE station_id = ? AND day_key = ?
                        ORDER BY start_time, id""",
                WINDOW_MAPPER, stationId, dayKey);
    }

    /**
     * 查询某卫星在某站某日、不早于给定时刻的全部预报窗口，按开始时刻升序。
     */
    public List<ContactWindow> findWindowsAfter(long stationId, long satelliteId,
                                                String dayKey, Instant notBefore) {
        return jdbc.query("""
                        SELECT id, satellite_id, station_id, start_time, end_time, day_key
                        FROM contact_window
                        WHERE station_id = ? AND satellite_id = ? AND day_key = ?
                          AND end_time > ?
                        ORDER BY start_time""",
                WINDOW_MAPPER, stationId, satelliteId, dayKey,
                java.sql.Timestamp.from(notBefore));
    }

    public long insertSatellite(String code, String name) {
        return satelliteInsert.executeAndReturnKey(
                new java.util.HashMap<>(java.util.Map.of("code", code, "name", name))).longValue();
    }

    public long insertStation(String code, String name) {
        return stationInsert.executeAndReturnKey(
                new java.util.HashMap<>(java.util.Map.of("code", code, "name", name))).longValue();
    }

    public long insertWindow(long satelliteId, long stationId,
                             Instant start, Instant end, String dayKey) {
        return windowInsert.executeAndReturnKey(new java.util.HashMap<>(java.util.Map.of(
                "satellite_id", satelliteId,
                "station_id", stationId,
                "start_time", java.sql.Timestamp.from(start),
                "end_time", java.sql.Timestamp.from(end),
                "day_key", dayKey
        ))).longValue();
    }
}
