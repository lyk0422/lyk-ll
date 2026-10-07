package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Optional;

/**
 * 储罐仓储：变更前以 FOR UPDATE 行锁串行化，版本条件更新兜底乐观锁。
 */
@Repository
public class TankRepository {

    private static final String COLUMNS = "id, station_id, tank_code, variety_no, capacity_kg, "
            + "safety_reserve_kg, balance_kg, reserved_kg, incoming_reserved_kg, version";

    private final JdbcTemplate jdbc;

    public TankRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long stationId, String tankCode, String varietyNo, BigDecimal capacityKg,
                       BigDecimal safetyReserveKg, BigDecimal balanceKg) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO tank
                                (station_id, tank_code, variety_no, capacity_kg, safety_reserve_kg,
                                 balance_kg, reserved_kg, incoming_reserved_kg, version, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, 0, 0, 1, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setString(2, tankCode);
            if (varietyNo == null) {
                ps.setNull(3, Types.VARCHAR);
            } else {
                ps.setString(3, varietyNo);
            }
            ps.setBigDecimal(4, capacityKg);
            ps.setBigDecimal(5, safetyReserveKg);
            ps.setBigDecimal(6, balanceKg);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }

    public Optional<Tank> findById(Long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank WHERE id = ?",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), id);
    }

    public Optional<Tank> findByCode(String tankCode) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank WHERE tank_code = ?",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), tankCode);
    }

    /**
     * 按绑定门站读取（不加锁），日结展示调拨转入/转出时使用；未绑定储罐返回空。
     */
    public Optional<Tank> findByStationId(Long stationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank WHERE station_id = ?",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), stationId);
    }

    /**
     * 事务内行级锁定读取，配合版本条件更新保证并发串行裁决。
     */
    public Optional<Tank> findByIdForUpdate(Long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), id);
    }

    /**
     * 按绑定门站行级锁定读取，压力联动消耗闸控使用；未绑定储罐返回空。
     */
    public Optional<Tank> findByStationIdForUpdate(Long stationId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank WHERE station_id = ? FOR UPDATE",
                rs -> rs.next() ? Optional.of(map(rs)) : Optional.empty(), stationId);
    }

    /**
     * 更新余额/预占/接收预占并递增版本；WHERE 携带期望版本兜底，返回受影响行数（应为 1）。
     */
    public int updateBalanceReservedVersion(Long id, BigDecimal balanceKg, BigDecimal reservedKg,
                                            BigDecimal incomingReservedKg, long newVersion,
                                            long expectedVersion) {
        return jdbc.update("""
                        UPDATE tank
                        SET balance_kg = ?, reserved_kg = ?, incoming_reserved_kg = ?, version = ?
                        WHERE id = ? AND version = ?
                        """,
                balanceKg, reservedKg, incomingReservedKg, newVersion, id, expectedVersion);
    }

    private Tank map(ResultSet rs) throws SQLException {
        return new Tank(
                rs.getLong("id"),
                rs.getLong("station_id"),
                rs.getString("tank_code"),
                rs.getString("variety_no"),
                rs.getBigDecimal("capacity_kg"),
                rs.getBigDecimal("safety_reserve_kg"),
                rs.getBigDecimal("balance_kg"),
                rs.getBigDecimal("reserved_kg"),
                rs.getBigDecimal("incoming_reserved_kg"),
                rs.getLong("version"));
    }
}
