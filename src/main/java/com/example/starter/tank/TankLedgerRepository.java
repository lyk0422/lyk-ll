package com.example.starter.tank;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

/**
 * 储罐台账仓储：每次余额/预占变动追加一条明细，守恒勾稽由明细重算验证。
 */
@Repository
public class TankLedgerRepository {

    private final JdbcTemplate jdbc;

    public TankLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(Long tankId, String entryType, BigDecimal amountKg,
                       BigDecimal balanceAfterKg, BigDecimal reservedAfterKg,
                       String refType, Long refId, String requestId) {
        jdbc.update("""
                        INSERT INTO tank_ledger
                            (tank_id, entry_type, amount_kg, balance_after_kg,
                             reserved_after_kg, ref_type, ref_id, request_id, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                tankId, entryType, amountKg, balanceAfterKg, reservedAfterKg,
                refType, refId, requestId);
    }

    public List<TankLedgerEntry> listByTank(Long tankId) {
        return jdbc.query("""
                        SELECT id, tank_id, entry_type, amount_kg, balance_after_kg,
                               reserved_after_kg, ref_type, ref_id, request_id, created_at
                        FROM tank_ledger WHERE tank_id = ? ORDER BY id
                        """,
                (rs, rowNum) -> new TankLedgerEntry(
                        rs.getLong("id"),
                        rs.getLong("tank_id"),
                        rs.getString("entry_type"),
                        rs.getBigDecimal("amount_kg"),
                        rs.getBigDecimal("balance_after_kg"),
                        rs.getBigDecimal("reserved_after_kg"),
                        rs.getString("ref_type"),
                        (Long) rs.getObject("ref_id"),
                        rs.getString("request_id"),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                tankId);
    }
}
