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
 * 储罐台账仓储：只追加不修改，余额/预占/接收预占守恒勾稽的数据基础。
 */
@Repository
public class TankLedgerRepository {

    private static final String COLUMNS = "id, tank_id, entry_type, balance_delta_kg, "
            + "reserved_delta_kg, incoming_reserved_delta_kg, balance_after_kg, "
            + "reserved_after_kg, incoming_reserved_after_kg, ref_type, ref_id, "
            + "request_id, biz_at";

    private final JdbcTemplate jdbc;

    public TankLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加分录（无接收预占变动），等价于 incoming 变动为 0。
     */
    public void insert(Long tankId, String entryType, BigDecimal balanceDeltaKg,
                       BigDecimal reservedDeltaKg, BigDecimal balanceAfterKg,
                       BigDecimal reservedAfterKg, String refType, Long refId,
                       String requestId, LocalDateTime bizAt) {
        insert(tankId, entryType, balanceDeltaKg, reservedDeltaKg, BigDecimal.ZERO,
                balanceAfterKg, reservedAfterKg, BigDecimal.ZERO,
                refType, refId, requestId, bizAt);
    }

    /**
     * 追加分录（含接收预占变动与快照）。
     */
    public void insert(Long tankId, String entryType, BigDecimal balanceDeltaKg,
                       BigDecimal reservedDeltaKg, BigDecimal incomingReservedDeltaKg,
                       BigDecimal balanceAfterKg, BigDecimal reservedAfterKg,
                       BigDecimal incomingReservedAfterKg, String refType, Long refId,
                       String requestId, LocalDateTime bizAt) {
        jdbc.update("""
                        INSERT INTO tank_ledger
                            (tank_id, entry_type, balance_delta_kg, reserved_delta_kg,
                             incoming_reserved_delta_kg, balance_after_kg, reserved_after_kg,
                             incoming_reserved_after_kg, ref_type, ref_id,
                             request_id, biz_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                tankId, entryType, balanceDeltaKg, reservedDeltaKg, incomingReservedDeltaKg,
                balanceAfterKg, reservedAfterKg, incomingReservedAfterKg,
                refType, refId, requestId, Timestamp.valueOf(bizAt));
    }

    public List<TankLedgerEntry> listByTank(Long tankId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM tank_ledger WHERE tank_id = ? ORDER BY id",
                (rs, rowNum) -> map(rs), tankId);
    }

    /**
     * 指定罐全部台账某变动列合计（守恒复算）。
     */
    public BigDecimal sumDeltaByTank(Long tankId, String deltaColumn) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(" + deltaColumn + "), 0) FROM tank_ledger WHERE tank_id = ?",
                BigDecimal.class, tankId);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 指定调拨单在指定罐上的某变动列合计（从调拨单复算双侧预占/余额变动）。
     */
    public BigDecimal sumDeltaByTransfer(Long tankId, Long transferId, String deltaColumn) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(" + deltaColumn + "), 0) FROM tank_ledger "
                        + "WHERE tank_id = ? AND ref_type = 'TRANSFER' AND ref_id = ?",
                BigDecimal.class, tankId, transferId);
        return value == null ? BigDecimal.ZERO : value;
    }

    /**
     * 指定罐在业务日期区间内某类分录的余额变动合计（日结展示调拨转入/转出）。
     */
    public BigDecimal sumBalanceDeltaByTypeAndBizAt(Long tankId, String entryType,
                                                    LocalDateTime fromInclusive,
                                                    LocalDateTime toExclusive) {
        BigDecimal value = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance_delta_kg), 0) FROM tank_ledger "
                        + "WHERE tank_id = ? AND entry_type = ? AND biz_at >= ? AND biz_at < ?",
                BigDecimal.class, tankId, entryType,
                Timestamp.valueOf(fromInclusive), Timestamp.valueOf(toExclusive));
        return value == null ? BigDecimal.ZERO : value;
    }

    private TankLedgerEntry map(ResultSet rs) throws SQLException {
        return new TankLedgerEntry(
                rs.getLong("id"),
                rs.getLong("tank_id"),
                rs.getString("entry_type"),
                rs.getBigDecimal("balance_delta_kg"),
                rs.getBigDecimal("reserved_delta_kg"),
                rs.getBigDecimal("incoming_reserved_delta_kg"),
                rs.getBigDecimal("balance_after_kg"),
                rs.getBigDecimal("reserved_after_kg"),
                rs.getBigDecimal("incoming_reserved_after_kg"),
                rs.getString("ref_type"),
                (Long) rs.getObject("ref_id"),
                rs.getString("request_id"),
                rs.getTimestamp("biz_at").toLocalDateTime());
    }
}
