package com.example.starter.tank;

import com.example.starter.web.dto.TankLedgerEntryView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 储罐台账仓储：只插不改，余额/预占变动全程守恒可勾稽；
 * 行带业务日期，日结只冻当日台账，后续补液不改旧行。
 */
@Repository
public class TankLedgerRepository {

    private final JdbcTemplate jdbc;

    public TankLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long tankId, Long stationId, String entryType, String refCode,
                       BigDecimal deltaBalanceKg, BigDecimal deltaReservedKg,
                       BigDecimal balanceAfterKg, BigDecimal reservedAfterKg, LocalDate bizDate) {
        jdbc.update("""
                        INSERT INTO tank_ledger
                            (tank_id, station_id, entry_type, ref_code, delta_balance_kg,
                             delta_reserved_kg, balance_after_kg, reserved_after_kg,
                             biz_date, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                tankId, stationId, entryType, refCode, deltaBalanceKg, deltaReservedKg,
                balanceAfterKg, reservedAfterKg, bizDate);
    }

    /**
     * 按门站与业务日期查询台账（守恒勾稽证据）。
     */
    public List<TankLedgerEntryView> findByStationAndDate(Long stationId, LocalDate bizDate) {
        return jdbc.query("""
                        SELECT id, entry_type, ref_code, delta_balance_kg, delta_reserved_kg,
                               balance_after_kg, reserved_after_kg, biz_date, created_at
                        FROM tank_ledger
                        WHERE station_id = ? AND biz_date = ? ORDER BY id
                        """,
                (rs, rowNum) -> new TankLedgerEntryView(
                        rs.getLong("id"),
                        rs.getString("entry_type"),
                        rs.getString("ref_code"),
                        rs.getBigDecimal("delta_balance_kg"),
                        rs.getBigDecimal("delta_reserved_kg"),
                        rs.getBigDecimal("balance_after_kg"),
                        rs.getBigDecimal("reserved_after_kg"),
                        rs.getDate("biz_date").toLocalDate(),
                        rs.getTimestamp("created_at").toLocalDateTime()),
                stationId, bizDate);
    }
}
