package com.example.starter.repository;

import com.example.starter.domain.LateTemperatureEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 归档后迟到温度事件数据访问。
 */
@Repository
public class LateTemperatureEventRepository {

    private static final RowMapper<LateTemperatureEvent> MAPPER = (rs, n) -> new LateTemperatureEvent(
            rs.getLong("id"), rs.getLong("unit_id"),
            rs.getObject("task_id") == null ? null : rs.getLong("task_id"),
            rs.getBigDecimal("temperature"), rs.getTimestamp("recorded_at").toInstant(),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public LateTemperatureEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 登记一条迟到温度事件。
     */
    public void insert(long unitId, Long taskId, BigDecimal temperature,
                       Instant recordedAt, Instant createdAt) {
        jdbc.update("INSERT INTO late_temperature_event"
                        + " (unit_id, task_id, temperature, recorded_at, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                unitId, taskId, temperature, Timestamp.from(recordedAt), Timestamp.from(createdAt));
    }

    /**
     * 查询单元全部迟到事件，按主键升序。
     */
    public List<LateTemperatureEvent> findByUnitId(long unitId) {
        return jdbc.query("SELECT id, unit_id, task_id, temperature, recorded_at, created_at"
                + " FROM late_temperature_event WHERE unit_id = ? ORDER BY id", MAPPER, unitId);
    }
}
