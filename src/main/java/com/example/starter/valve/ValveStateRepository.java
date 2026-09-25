package com.example.starter.valve;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * 加注阀状态仓储：所有读取均加行锁（FOR UPDATE），保证并发裁决串行化。
 */
@Repository
public class ValveStateRepository {

    private final JdbcTemplate jdbc;

    public ValveStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 事务内行级锁定读取门站阀门状态。
     */
    public ValveState selectForUpdate(Long stationId) {
        return jdbc.query("""
                        SELECT station_id, injection_locked, alarm_active, breach_count,
                               current_injection_rate_mg_m3, current_pressure_mpa
                        FROM valve_state WHERE station_id = ? FOR UPDATE
                        """,
                        rs -> {
                            if (!rs.next()) {
                                return null;
                            }
                            return new ValveState(
                                    rs.getLong("station_id"),
                                    rs.getBoolean("injection_locked"),
                                    rs.getBoolean("alarm_active"),
                                    rs.getInt("breach_count"),
                                    rs.getBigDecimal("current_injection_rate_mg_m3"),
                                    rs.getBigDecimal("current_pressure_mpa"));
                        },
                        stationId);
    }

    /**
     * 不加锁读取，用于重放后返回当前阀门态以外的只读场景。
     */
    public ValveState find(Long stationId) {
        return jdbc.query("""
                        SELECT station_id, injection_locked, alarm_active, breach_count,
                               current_injection_rate_mg_m3, current_pressure_mpa
                        FROM valve_state WHERE station_id = ?
                        """,
                        rs -> rs.next() ? new ValveState(
                                rs.getLong("station_id"),
                                rs.getBoolean("injection_locked"),
                                rs.getBoolean("alarm_active"),
                                rs.getInt("breach_count"),
                                rs.getBigDecimal("current_injection_rate_mg_m3"),
                                rs.getBigDecimal("current_pressure_mpa")) : null,
                        stationId);
    }

    public void updateState(Long stationId, boolean locked, boolean alarm, int breachCount,
                            BigDecimal injectionRateMgM3, BigDecimal pressureMpa) {
        jdbc.update("""
                        UPDATE valve_state
                        SET injection_locked = ?, alarm_active = ?, breach_count = ?,
                            current_injection_rate_mg_m3 = ?, current_pressure_mpa = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE station_id = ?
                        """,
                locked, alarm, breachCount, injectionRateMgM3, pressureMpa, stationId);
    }

    /**
     * 仅更新加注速率与压力（压力联动使用），锁阀/告警/越限计数保持不变。
     */
    public void updateInjectionAndPressure(Long stationId, BigDecimal injectionRateMgM3,
                                           BigDecimal pressureMpa) {
        jdbc.update("""
                        UPDATE valve_state
                        SET current_injection_rate_mg_m3 = ?, current_pressure_mpa = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE station_id = ?
                        """,
                injectionRateMgM3, pressureMpa, stationId);
    }
}
