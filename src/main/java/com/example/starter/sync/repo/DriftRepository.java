package com.example.starter.sync.repo;

import java.time.Clock;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.sync.SensorType;
import com.example.starter.sync.domain.DriftParams;

/**
 * 漂移校正参数仓储。同一传感器 (sensor_type, version) 唯一。
 */
@Repository
public class DriftRepository {

    private static final RowMapper<DriftParams> ROW_MAPPER = (rs, n) -> new DriftParams(
            SensorType.valueOf(rs.getString("sensor_type")),
            rs.getInt("version"),
            rs.getLong("offset_ms"),
            rs.getLong("effective_from"));

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DriftRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 持久化一条漂移参数版本。
     */
    public void insert(DriftParams params) {
        jdbc.update("INSERT INTO drift_correction (sensor_type, version, offset_ms, effective_from, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                params.sensorType().name(), params.version(), params.offsetMs(),
                params.effectiveFrom(), clock.millis());
    }

    /**
     * 读取全部漂移参数（按传感器与版本升序）。
     */
    public List<DriftParams> findAll() {
        return jdbc.query("SELECT * FROM drift_correction ORDER BY sensor_type, version", ROW_MAPPER);
    }
}
