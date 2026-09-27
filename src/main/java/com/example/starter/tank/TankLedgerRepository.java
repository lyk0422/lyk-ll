package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 储罐交接台账流水仓储：只追加，不修改、不删除。
 */
@Repository
public class TankLedgerRepository {

    private final JdbcTemplate jdbc;

    public TankLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(Long stationId, Long tankId, String requestId, String entryType,
                       BigDecimal amountKg, BigDecimal balanceAfterKg, BigDecimal reservedAfterKg,
                       LocalDateTime bizTime) {
        jdbc.update("""
                        INSERT INTO tank_ledger
                            (station_id, tank_id, request_id, entry_type, amount_kg,
                             balance_after_kg, reserved_after_kg, biz_time, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                stationId, tankId, requestId, entryType, amountKg,
                balanceAfterKg, reservedAfterKg, Timestamp.valueOf(bizTime));
    }

    public List<LedgerEntry> findByTank(Long tankId) {
        return jdbc.query("""
                        SELECT id, station_id, tank_id, request_id, entry_type, amount_kg,
                               balance_after_kg, reserved_after_kg, biz_time, created_at
                        FROM tank_ledger
                        WHERE tank_id = ?
                        ORDER BY id ASC
                        """,
                (rs, rowNum) -> map(rs), tankId);
    }

    private LedgerEntry map(ResultSet rs) throws SQLException {
        return new LedgerEntry(
                rs.getLong("id"),
                rs.getLong("station_id"),
                rs.getLong("tank_id"),
                rs.getString("request_id"),
                rs.getString("entry_type"),
                rs.getBigDecimal("amount_kg"),
                rs.getBigDecimal("balance_after_kg"),
                rs.getBigDecimal("reserved_after_kg"),
                rs.getTimestamp("biz_time").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime());
    }
}
