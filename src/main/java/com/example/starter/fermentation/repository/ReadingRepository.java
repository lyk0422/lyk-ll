package com.example.starter.fermentation.repository;

import com.example.starter.fermentation.domain.CurvePhase;
import com.example.starter.fermentation.domain.DiacetylTest;
import com.example.starter.fermentation.domain.TemperatureReading;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 温度采样、双乙酰检测与迟到事件数据访问。
 */
@Repository
public class ReadingRepository {

    private static final RowMapper<TemperatureReading> READING_MAPPER = (rs, rowNum) -> new TemperatureReading(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getInt("curve_version"),
            CurvePhase.valueOf(rs.getString("phase")),
            rs.getTimestamp("sampled_at").toLocalDateTime(),
            rs.getBigDecimal("temperature"),
            rs.getBigDecimal("setpoint"),
            rs.getBigDecimal("deviation"),
            rs.getInt("out_of_tolerance") != 0,
            rs.getInt("consecutive_out"),
            rs.getInt("alarm_triggered") != 0,
            rs.getInt("late") != 0);

    private static final RowMapper<DiacetylTest> DIACETYL_MAPPER = (rs, rowNum) -> new DiacetylTest(
            rs.getLong("id"),
            rs.getLong("batch_id"),
            rs.getTimestamp("tested_at").toLocalDateTime(),
            rs.getBigDecimal("value_mg_per_liter"),
            rs.getInt("late") != 0);

    private final JdbcTemplate jdbc;

    public ReadingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入温度采样记录，返回记录 ID。
     */
    public long insertReading(long batchId, int curveVersion, CurvePhase phase, LocalDateTime sampledAt,
                              BigDecimal temperature, BigDecimal setpoint, BigDecimal deviation,
                              boolean outOfTolerance, int consecutiveOut, boolean alarmTriggered, boolean late) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO temperature_reading (batch_id, curve_version, phase, sampled_at, temperature,"
                            + " setpoint, deviation, out_of_tolerance, consecutive_out, alarm_triggered, late)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setInt(2, curveVersion);
            ps.setString(3, phase.name());
            ps.setTimestamp(4, Timestamp.valueOf(sampledAt));
            ps.setBigDecimal(5, temperature);
            ps.setBigDecimal(6, setpoint);
            ps.setBigDecimal(7, deviation);
            ps.setInt(8, outOfTolerance ? 1 : 0);
            ps.setInt(9, consecutiveOut);
            ps.setInt(10, alarmTriggered ? 1 : 0);
            ps.setInt(11, late ? 1 : 0);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询批次某阶段的非迟到采样，按采样时间（同刻按 ID）升序，用于连续超差复算。
     */
    public List<TemperatureReading> findReadings(long batchId, CurvePhase phase) {
        return jdbc.query(
                "SELECT * FROM temperature_reading WHERE batch_id = ? AND phase = ? AND late = 0"
                        + " ORDER BY sampled_at, id",
                READING_MAPPER, batchId, phase.name());
    }

    /**
     * 写入双乙酰检测记录，返回记录 ID。
     */
    public long insertDiacetyl(long batchId, LocalDateTime testedAt, BigDecimal valueMgPerLiter, boolean late) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO diacetyl_test (batch_id, tested_at, value_mg_per_liter, late)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, batchId);
            ps.setTimestamp(2, Timestamp.valueOf(testedAt));
            ps.setBigDecimal(3, valueMgPerLiter);
            ps.setInt(4, late ? 1 : 0);
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * 查询批次非迟到双乙酰检测链，按检测时间（同刻按 ID）升序。
     */
    public List<DiacetylTest> findDiacetylTests(long batchId) {
        return jdbc.query(
                "SELECT * FROM diacetyl_test WHERE batch_id = ? AND late = 0 ORDER BY tested_at, id",
                DIACETYL_MAPPER, batchId);
    }

    /**
     * 记录迟到事件（批次终态后到达的数据）。
     */
    public void insertLateEvent(long batchId, String eventType, String payload, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO late_event (batch_id, event_type, payload, created_at) VALUES (?, ?, ?, ?)",
                batchId, eventType, payload, Timestamp.valueOf(createdAt));
    }

    /**
     * 统计批次迟到事件数（用于测试断言）。
     */
    public int countLateEvents(long batchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_event WHERE batch_id = ?", Integer.class, batchId);
        return count == null ? 0 : count;
    }
}
