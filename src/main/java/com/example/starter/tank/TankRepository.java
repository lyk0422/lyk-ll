package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * 储罐仓储：行级锁定读取 + 版本号自增更新，串行化同一储罐的全部计量操作。
 */
@Repository
public class TankRepository {

    private final JdbcTemplate jdbc;

    public TankRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_COLUMNS = """
            SELECT id, station_id, tank_code, capacity_kg, safety_reserve_kg,
                   balance_kg, reserved_kg, version
            FROM odorant_tank
            """;

    public Optional<Tank> findByStationId(Long stationId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE station_id = ?",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), stationId);
    }

    public Optional<Tank> findByStationIdForUpdate(Long stationId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE station_id = ? FOR UPDATE",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), stationId);
    }

    public Optional<Tank> findByCode(String tankCode) {
        return jdbc.query(SELECT_COLUMNS + " WHERE tank_code = ?",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), tankCode);
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

    /**
     * 更新余额/预占并把版本号加 1（每次成功写操作必变更新版本）。
     */
    public void updateQuantities(Long tankId, BigDecimal balanceKg, BigDecimal reservedKg) {
        int rows = jdbc.update("""
                        UPDATE odorant_tank
                        SET balance_kg = ?, reserved_kg = ?, version = version + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE id = ?
                        """,
                balanceKg, reservedKg, tankId);
        if (rows != 1) {
            throw new IllegalStateException("tank update affected " + rows + " rows: " + tankId);
        }
    }

    private Tank map(ResultSet rs) throws SQLException {
        return new Tank(
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
