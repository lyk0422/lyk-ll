package com.example.starter.forestry.repo;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.forestry.dto.QuotaLedgerView;
import com.example.starter.forestry.po.AnnualQuotaPO;

/**
 * 年度采伐限额仓储：限额分档（株数与蓄积双口径）读取与台账占用/释放。
 * 所有占用/查询必须在同档位串行锁（SELECT ... FOR UPDATE）保护下进行。
 */
@Repository
public class QuotaRepository {

    private final JdbcTemplate jdbc;

    public QuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 双口径占用净额。
     *
     * @param treeCount 株数口径占用净额，单位株
     * @param volume    蓄积口径占用净额，单位立方米
     */
    public record HeldAmount(long treeCount, BigDecimal volume) {
    }

    private AnnualQuotaPO mapTier(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AnnualQuotaPO(
                rs.getLong("id"),
                rs.getString("species_code"),
                rs.getInt("quota_year"),
                rs.getInt("total_trees"),
                rs.getBigDecimal("total_volume"));
    }

    /**
     * 读取分档年度限额（双口径），不存在返回 null。
     */
    public AnnualQuotaPO findTier(String speciesCode, int year) {
        List<AnnualQuotaPO> rows = jdbc.query(
                "SELECT id, species_code, quota_year, total_trees, total_volume"
                        + " FROM annual_quota WHERE species_code = ? AND quota_year = ?",
                (rs, n) -> mapTier(rs), speciesCode, year);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 锁定分档年度限额行（SELECT ... FOR UPDATE）：同档位发证据此全局串行。
     * 不存在返回 null。
     */
    public AnnualQuotaPO lockTierForUpdate(String speciesCode, int year) {
        List<AnnualQuotaPO> rows = jdbc.query(
                "SELECT id, species_code, quota_year, total_trees, total_volume"
                        + " FROM annual_quota WHERE species_code = ? AND quota_year = ? FOR UPDATE",
                (rs, n) -> mapTier(rs), speciesCode, year);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 汇总该档位当年台账净额（HOLD 正、RELEASE 负），株数与蓄积双口径。
     */
    public HeldAmount sumHeld(String speciesCode, int year) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(tree_count), 0) AS held_trees, COALESCE(SUM(volume), 0) AS held_volume"
                        + " FROM quota_ledger WHERE species_code = ? AND quota_year = ?",
                (rs, n) -> new HeldAmount(rs.getLong("held_trees"), rs.getBigDecimal("held_volume")),
                speciesCode, year);
    }

    /**
     * 追加一笔台账（发证占用或逾期释放），株数与蓄积双口径同笔记账。
     */
    public void appendLedger(String speciesCode, int year, String applicationId, String licenseId,
                             int treeCount, BigDecimal volume, String action, LocalDate date) {
        jdbc.update("""
                        INSERT INTO quota_ledger
                            (species_code, quota_year, application_id, license_id, tree_count, volume, action, created_date)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                speciesCode, year, applicationId, licenseId, treeCount, volume, action, Date.valueOf(date));
    }

    /**
     * 双口径台账输出：按树种档位汇总指定年度限额与占用净额。
     * 占用量由台账流水实时汇总（可复算），结果按树种编码排序，重复查询稳定。
     */
    public List<QuotaLedgerView> ledgerView(int year) {
        return jdbc.query("""
                        SELECT q.species_code, q.quota_year, q.total_trees, q.total_volume,
                               COALESCE(SUM(l.tree_count), 0) AS held_trees,
                               COALESCE(SUM(l.volume), 0) AS held_volume
                          FROM annual_quota q
                          LEFT JOIN quota_ledger l
                            ON l.species_code = q.species_code AND l.quota_year = q.quota_year
                         WHERE q.quota_year = ?
                         GROUP BY q.species_code, q.quota_year, q.total_trees, q.total_volume
                         ORDER BY q.species_code
                        """,
                (rs, n) -> {
                    int totalTrees = rs.getInt("total_trees");
                    long heldTrees = rs.getLong("held_trees");
                    BigDecimal totalVolume = rs.getBigDecimal("total_volume");
                    BigDecimal heldVolume = rs.getBigDecimal("held_volume");
                    return new QuotaLedgerView(
                            rs.getString("species_code"),
                            rs.getInt("quota_year"),
                            totalTrees,
                            totalVolume,
                            (int) heldTrees,
                            heldVolume,
                            (int) (totalTrees - heldTrees),
                            totalVolume.subtract(heldVolume));
                },
                year);
    }
}
