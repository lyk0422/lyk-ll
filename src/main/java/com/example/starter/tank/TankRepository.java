package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 储罐仓储：所有变更前置行锁（FOR UPDATE）读取，保证并发计量串行化。
 */
@Repository
public class TankRepository {

    private final JdbcTemplate jdbc;

    public TankRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 事务内行级锁定读取门站绑定储罐；未绑定返回空（旧门站行为不变）。
     */
    public Optional<Tank> findForUpdate(Long stationId) {
        return jdbc.query("""
                        SELECT id, station_id, capacity_kg, safety_reserve_kg,
                               balance_kg, reserved_kg, version
                        FROM odorant_tank WHERE station_id = ? FOR UPDATE
                        """,
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(),
                stationId);
    }

    /**
     * 不加锁只读查询（状态查询接口）。
     */
    public Optional<Tank> find(Long stationId) {
        return jdbc.query("""
                        SELECT id, station_id, capacity_kg, safety_reserve_kg,
                               balance_kg, reserved_kg, version
                        FROM odorant_tank WHERE station_id = ?
                        """,
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(),
                stationId);
    }

    public Long insert(Long stationId, BigDecimal capacityKg, BigDecimal safetyReserveKg,
                       BigDecimal balanceKg) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO odorant_tank
                                (station_id, capacity_kg, safety_reserve_kg, balance_kg,
                                 reserved_kg, version, created_at, updated_at)
                            VALUES (?, ?, ?, ?, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setBigDecimal(2, capacityKg);
            ps.setBigDecimal(3, safetyReserveKg);
            ps.setBigDecimal(4, balanceKg);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    /**
     * 更新容量与安全储备（配置调整），版本加 1。
     */
    public void updateConfig(Long tankId, BigDecimal capacityKg, BigDecimal safetyReserveKg,
                             long newVersion) {
        jdbc.update("""
                        UPDATE odorant_tank
                        SET capacity_kg = ?, safety_reserve_kg = ?, version = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                capacityKg, safetyReserveKg, newVersion, tankId);
    }

    /**
     * 更新余额与预占（预占/回执/取消/补液共用），版本加 1。
     */
    public void updateBalance(Long tankId, BigDecimal balanceKg, BigDecimal reservedKg,
                              long newVersion) {
        jdbc.update("""
                        UPDATE odorant_tank
                        SET balance_kg = ?, reserved_kg = ?, version = ?,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                balanceKg, reservedKg, newVersion, tankId);
    }

    private Tank map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Tank(
                rs.getLong("id"),
                rs.getLong("station_id"),
                rs.getBigDecimal("capacity_kg"),
                rs.getBigDecimal("safety_reserve_kg"),
                rs.getBigDecimal("balance_kg"),
                rs.getBigDecimal("reserved_kg"),
                rs.getLong("version"));
    }
}
