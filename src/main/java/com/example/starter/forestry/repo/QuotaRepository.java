package com.example.starter.forestry.repo;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 年度采伐限额仓储：限额分档总额读取与台账占用/释放。
 * 所有占用/查询必须在同档位串行锁（见 Service 层 GET_SERIALIZED）保护下进行。
 */
@Repository
public class QuotaRepository {

    private final JdbcTemplate jdbc;

    public QuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取分档年度限额总额，不存在返回 null。
     */
    public BigDecimal findTotal(String speciesCode, int year) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT total_volume FROM annual_quota WHERE species_code = ? AND quota_year = ?",
                (rs, n) -> rs.getBigDecimal("total_volume"), speciesCode, year);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 锁定分档年度限额行（SELECT ... FOR UPDATE）：同档位发证据此全局串行。
     * 不存在返回 null。
     */
    public BigDecimal findTotalForUpdate(String speciesCode, int year) {
        List<BigDecimal> rows = jdbc.query(
                "SELECT total_volume FROM annual_quota WHERE species_code = ? AND quota_year = ? FOR UPDATE",
                (rs, n) -> rs.getBigDecimal("total_volume"), speciesCode, year);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 汇总该档位当年台账净额（HOLD 正、RELEASE 负）。
     */
    public BigDecimal sumHeld(String speciesCode, int year) {
        BigDecimal held = jdbc.queryForObject(
                "SELECT COALESCE(SUM(volume), 0) FROM quota_ledger WHERE species_code = ? AND quota_year = ?",
                BigDecimal.class, speciesCode, year);
        return held == null ? BigDecimal.ZERO : held;
    }

    /**
     * 追加一笔台账（发证占用或逾期释放）。
     */
    public void appendLedger(String speciesCode, int year, String applicationId, String licenseId,
                             BigDecimal volume, String action, LocalDate date) {
        jdbc.update("""
                        INSERT INTO quota_ledger
                            (species_code, quota_year, application_id, license_id, volume, action, created_date)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                speciesCode, year, applicationId, licenseId, volume, action, Date.valueOf(date));
    }
}
