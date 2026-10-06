package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 日结快照数据访问。快照由调光留痕复算后写入，(circuit_id, settle_date) 唯一，重复日结覆盖。
 */
@Repository
public class SettlementRepository {

    /** 日结快照行。 */
    public record SettlementRow(long circuitId, LocalDate settleDate, int dimCount, long energyMwh) {
    }

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 写入或覆盖某回路某日快照。 */
    public void upsert(long circuitId, LocalDate date, int dimCount, long energyMwh, Instant createdAt) {
        int updated = jdbc.update("UPDATE daily_settlement SET dim_count = ?, energy_mwh = ?, created_at = ?"
                        + " WHERE circuit_id = ? AND settle_date = ?",
                dimCount, energyMwh, Timestamp.from(createdAt), circuitId, java.sql.Date.valueOf(date));
        if (updated == 0) {
            jdbc.update("INSERT INTO daily_settlement (circuit_id, settle_date, dim_count, energy_mwh, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    circuitId, java.sql.Date.valueOf(date), dimCount, energyMwh, Timestamp.from(createdAt));
        }
    }

    /** 查询某日全部回路快照。 */
    public List<SettlementRow> findByDate(LocalDate date) {
        return jdbc.query("SELECT circuit_id, settle_date, dim_count, energy_mwh FROM daily_settlement"
                        + " WHERE settle_date = ? ORDER BY circuit_id",
                this::map, java.sql.Date.valueOf(date));
    }

    private SettlementRow map(ResultSet rs, int rowNum) throws SQLException {
        return new SettlementRow(
                rs.getLong("circuit_id"),
                rs.getDate("settle_date").toLocalDate(),
                rs.getInt("dim_count"),
                rs.getLong("energy_mwh"));
    }
}
