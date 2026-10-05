package com.example.starter.forestry.repo;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.forestry.po.QuotaHeldPO;
import com.example.starter.forestry.po.QuotaLedgerEntryPO;
import com.example.starter.forestry.po.QuotaTierPO;

/**
 * 年度采伐限额仓储：限额分档总额读取与双口径台账占用/释放。
 * 所有占用/查询必须在同档位串行锁（SELECT ... FOR UPDATE）保护下进行。
 */
@Repository
public class QuotaRepository {

    private final JdbcTemplate jdbc;

    public QuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取分档年度限额双口径总额，不存在返回 null。
     */
    public QuotaTierPO findTier(String speciesCode, int year) {
        List<QuotaTierPO> rows = jdbc.query(
                "SELECT total_trees, total_volume FROM annual_quota"
                        + " WHERE species_code = ? AND quota_year = ?",
                (rs, n) -> new QuotaTierPO(rs.getInt("total_trees"), rs.getBigDecimal("total_volume")),
                speciesCode, year);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 锁定分档年度限额行（SELECT ... FOR UPDATE）：同档位发证据此全局串行。
     * 不存在返回 null。
     */
    public QuotaTierPO findTierForUpdate(String speciesCode, int year) {
        List<QuotaTierPO> rows = jdbc.query(
                "SELECT total_trees, total_volume FROM annual_quota"
                        + " WHERE species_code = ? AND quota_year = ? FOR UPDATE",
                (rs, n) -> new QuotaTierPO(rs.getInt("total_trees"), rs.getBigDecimal("total_volume")),
                speciesCode, year);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 汇总该档位当年台账双口径净额（HOLD 正、RELEASE 负）。
     */
    public QuotaHeldPO sumHeld(String speciesCode, int year) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(trees), 0) AS trees, COALESCE(SUM(volume), 0) AS volume"
                        + " FROM quota_ledger WHERE species_code = ? AND quota_year = ?",
                (rs, n) -> new QuotaHeldPO(rs.getLong("trees"), rs.getBigDecimal("volume")),
                speciesCode, year);
    }

    /**
     * 追加一笔双口径台账（发证占用或逾期释放）。
     */
    public void appendLedger(String speciesCode, int year, String applicationId, String licenseId,
                             int trees, java.math.BigDecimal volume, String action, LocalDate date) {
        jdbc.update("""
                        INSERT INTO quota_ledger
                            (species_code, quota_year, application_id, license_id, trees, volume, action, created_date)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                speciesCode, year, applicationId, licenseId, trees, volume, action, Date.valueOf(date));
    }

    /**
     * 按记账顺序（自增主键）列出该档位当年全部台账明细，供台账视图稳定复算。
     */
    public List<QuotaLedgerEntryPO> listLedgerEntries(String speciesCode, int year) {
        return jdbc.query("""
                        SELECT id, application_id, license_id, trees, volume, action, created_date
                          FROM quota_ledger
                         WHERE species_code = ? AND quota_year = ?
                         ORDER BY id
                        """,
                (rs, n) -> new QuotaLedgerEntryPO(
                        rs.getLong("id"),
                        rs.getString("application_id"),
                        rs.getString("license_id"),
                        rs.getInt("trees"),
                        rs.getBigDecimal("volume"),
                        rs.getString("action"),
                        rs.getDate("created_date").toLocalDate()),
                speciesCode, year);
    }
}
