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
 * 储罐台账仓储：只追加不修改，余额/预占守恒勾稽的数据基础。
 */
@Repository
public class TankLedgerRepository {

    private static final String COLUMNS = "id, tank_id, entry_type, balance_delta_kg, "
            + "reserved_delta_kg, balance_after_kg, reserved_after_kg, ref_type, ref_id, "
            + "request_id, biz_at";

    private final JdbcTemplate jdbc;

    public TankLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long tankId, String entryType, BigDecimal balanceDeltaKg,
                       BigDecimal reservedDeltaKg, BigDecimal balanceAfterKg,
                       BigDecimal reservedAfterKg, String refType, Long refId,
                       String requestId, LocalDateTime bizAt) {
        jdbc.update("""
                        INSERT INTO tank_ledger
                            (tank_id, entry_type, balance_delta_kg, reserved_delta_kg,
                             balance_after_kg, reserved_after_kg, ref_type, ref_id,
                             request_id, biz_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                tankId, entryType, balanceDeltaKg, reservedDeltaKg, balanceAfterKg,
                reservedAfterKg, refType, refId, requestId, Timestamp.valueOf(bizAt));
    }

    public List<TankLedgerEntry> listByTank(Long tankId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank_ledger WHERE tank_id = ? ORDER BY id",
                (rs, rowNum) -> map(rs), tankId);
    }

    private TankLedgerEntry map(ResultSet rs) throws SQLException {
        return new TankLedgerEntry(
                rs.getLong("id"),
                rs.getLong("tank_id"),
                rs.getString("entry_type"),
                rs.getBigDecimal("balance_delta_kg"),
                rs.getBigDecimal("reserved_delta_kg"),
                rs.getBigDecimal("balance_after_kg"),
                rs.getBigDecimal("reserved_after_kg"),
                rs.getString("ref_type"),
                (Long) rs.getObject("ref_id"),
                rs.getString("request_id"),
                rs.getTimestamp("biz_at").toLocalDateTime());
    }
}
