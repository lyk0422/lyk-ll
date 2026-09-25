package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 渔船、鱼种与配额台账的数据访问。台账行级锁（FOR UPDATE）用于核销并发控制。
 */
@Repository
public class QuotaDao {

    /** 配额台账行。remaining 由 totalQuota - usedQuota 复算，不落库。 */
    public record LedgerRow(long id, long vesselId, String speciesCode, int quotaYear,
                            BigDecimal totalQuota, BigDecimal usedQuota, boolean frozen) {
        public BigDecimal remaining() {
            return totalQuota.subtract(usedQuota);
        }
    }

    private final JdbcTemplate jdbc;

    public QuotaDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 锁定渔船行以串行化该船的航次创建；渔船不存在时返回 false。 */
    public boolean lockVessel(long vesselId) {
        List<Long> ids = jdbc.query("SELECT id FROM vessel WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getLong(1), vesselId);
        return !ids.isEmpty();
    }

    /** 全部保护鱼种编码。 */
    public Set<String> protectedSpeciesCodes() {
        return new HashSet<>(jdbc.query("SELECT code FROM species WHERE protected_flag = TRUE",
                (rs, n) -> rs.getString(1)));
    }

    /** 查询渔船某年度的全部台账行（不加锁，用于台账查询与申报校验）。 */
    public List<LedgerRow> listLedger(long vesselId, int quotaYear) {
        return jdbc.query("SELECT id, vessel_id, species_code, quota_year, total_quota, used_quota, frozen"
                        + " FROM quota_ledger WHERE vessel_id = ? AND quota_year = ? ORDER BY species_code",
                (rs, n) -> mapLedger(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBoolean(7)),
                vesselId, quotaYear);
    }

    /** 按鱼种顺序锁定渔船某年度全部台账行（核销与冻结联动使用，避免并发死锁）。 */
    public List<LedgerRow> lockLedgers(long vesselId, int quotaYear) {
        return jdbc.query("SELECT id, vessel_id, species_code, quota_year, total_quota, used_quota, frozen"
                        + " FROM quota_ledger WHERE vessel_id = ? AND quota_year = ?"
                        + " ORDER BY species_code FOR UPDATE",
                (rs, n) -> mapLedger(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBoolean(7)),
                vesselId, quotaYear);
    }

    /** 查询单条台账（不加锁）。 */
    public Optional<LedgerRow> findLedger(long vesselId, String speciesCode, int quotaYear) {
        List<LedgerRow> rows = jdbc.query("SELECT id, vessel_id, species_code, quota_year, total_quota, used_quota, frozen"
                        + " FROM quota_ledger WHERE vessel_id = ? AND species_code = ? AND quota_year = ?",
                (rs, n) -> mapLedger(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                        rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getBoolean(7)),
                vesselId, speciesCode, quotaYear);
        return rows.stream().findFirst();
    }

    /** 扣减台账已核销量（调用方须已持有台账行锁并完成余额校验）。 */
    public void deduct(long ledgerId, BigDecimal quantity) {
        jdbc.update("UPDATE quota_ledger SET used_quota = used_quota + ? WHERE id = ?", quantity, ledgerId);
    }

    /** 冻结或解除冻结渔船某年度全部台账行。 */
    public void setFrozen(long vesselId, int quotaYear, boolean frozen) {
        jdbc.update("UPDATE quota_ledger SET frozen = ? WHERE vessel_id = ? AND quota_year = ?",
                frozen, vesselId, quotaYear);
    }

    private LedgerRow mapLedger(long id, long vesselId, String speciesCode, int quotaYear,
                                BigDecimal totalQuota, BigDecimal usedQuota, boolean frozen) {
        return new LedgerRow(id, vesselId, speciesCode, quotaYear, totalQuota, usedQuota, frozen);
    }
}
