package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.Optional;

/**
 * 储罐仓储：所有变更前置行锁（FOR UPDATE）读取，保证并发裁决串行化。
 */
@Repository
public class TankRepository {

    private final JdbcTemplate jdbc;

    public TankRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long stationId, String tankCode, BigDecimal capacityKg,
                       BigDecimal safetyReserveKg, BigDecimal initialBalanceKg) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO odorant_tank
                                (station_id, tank_code, capacity_kg, safety_reserve_kg,
                                 balance_kg, reserved_kg, version, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setString(2, tankCode);
            ps.setBigDecimal(3, capacityKg);
            ps.setBigDecimal(4, safetyReserveKg);
            ps.setBigDecimal(5, initialBalanceKg);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public Optional<OdorantTank> findByStationId(Long stationId) {
        return jdbc.query("""
                        SELECT id, station_id, tank_code, capacity_kg, safety_reserve_kg,
                               balance_kg, reserved_kg, version
                        FROM odorant_tank WHERE station_id = ?
                        """,
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(),
                stationId);
    }

    /**
     * 事务内行级锁定读取门站储罐；未绑定返回 null。
     */
    public OdorantTank selectForUpdateByStationId(Long stationId) {
        return jdbc.query("""
                        SELECT id, station_id, tank_code, capacity_kg, safety_reserve_kg,
                               balance_kg, reserved_kg, version
                        FROM odorant_tank WHERE station_id = ? FOR UPDATE
                        """,
                rs -> rs.next() ? map(rs) : null,
                stationId);
    }

    /**
     * 事务内行级锁定按主键读取；不存在返回 null。
     */
    public OdorantTank selectForUpdateById(Long tankId) {
        return jdbc.query("""
                        SELECT id, station_id, tank_code, capacity_kg, safety_reserve_kg,
                               balance_kg, reserved_kg, version
                        FROM odorant_tank WHERE id = ? FOR UPDATE
                        """,
                rs -> rs.next() ? map(rs) : null,
                tankId);
    }

    /**
     * 应用一次变更并递增版本：余额与预占由调用方按守恒规则计算。
     */
    public void applyMutation(Long tankId, BigDecimal balanceKg, BigDecimal reservedKg) {
        jdbc.update("""
                        UPDATE odorant_tank
                        SET balance_kg = ?, reserved_kg = ?, version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                balanceKg, reservedKg, tankId);
    }

    private OdorantTank map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OdorantTank(
                rs.getLong("id"),
                rs.getLong("station_id"),
                rs.getString("tank_code"),
                rs.getBigDecimal("capacity_kg"),
                rs.getBigDecimal("safety_reserve_kg"),
                rs.getBigDecimal("balance_kg"),
                rs.getBigDecimal("reserved_kg"),
                rs.getInt("version"));
    }
}
