package com.example.starter.forestry.repo;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.forestry.po.LicensePO;

/**
 * 采伐许可证仓储。
 */
@Repository
public class LicenseRepository {

    private final JdbcTemplate jdbc;

    public LicenseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = """
            id, application_id, operator_id, subplot_id, species_code, quota_year, tree_count, volume,
            status, issue_date, expire_date, harvest_date, void_date
            """;

    private LicensePO mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new LicensePO(
                rs.getString("id"),
                rs.getString("application_id"),
                rs.getString("operator_id"),
                rs.getString("subplot_id"),
                rs.getString("species_code"),
                rs.getInt("quota_year"),
                rs.getInt("tree_count"),
                rs.getBigDecimal("volume"),
                rs.getString("status"),
                rs.getDate("issue_date").toLocalDate(),
                rs.getDate("expire_date").toLocalDate(),
                rs.getDate("harvest_date") == null ? null : rs.getDate("harvest_date").toLocalDate(),
                rs.getDate("void_date") == null ? null : rs.getDate("void_date").toLocalDate());
    }

    public void insert(LicensePO po) {
        jdbc.update("""
                        INSERT INTO harvest_license
                            (id, application_id, operator_id, subplot_id, species_code, quota_year,
                             tree_count, volume, status, issue_date, expire_date, harvest_date, void_date)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                po.id(), po.applicationId(), po.operatorId(), po.subplotId(), po.speciesCode(), po.quotaYear(),
                po.treeCount(), po.volume(), po.status(), Date.valueOf(po.issueDate()), Date.valueOf(po.expireDate()),
                po.harvestDate() == null ? null : Date.valueOf(po.harvestDate()),
                po.voidDate() == null ? null : Date.valueOf(po.voidDate()));
    }

    public LicensePO findById(String id) {
        List<LicensePO> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM harvest_license WHERE id = ?", (rs, n) -> mapRow(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 条件置为已采伐：仅当当前状态为 ISSUED 时成功，保证“作废与采伐确认并发形成唯一终态”。
     *
     * @return 受影响行数；0 表示已被并发作废或已确认
     */
    public int markHarvestedIfIssued(String id, LocalDate harvestDate) {
        return jdbc.update("""
                        UPDATE harvest_license
                           SET status = 'HARVESTED', harvest_date = ?
                         WHERE id = ? AND status = 'ISSUED'
                        """,
                Date.valueOf(harvestDate), id);
    }

    /**
     * 条件作废：仅当当前状态为 ISSUED 时成功。
     *
     * @return 受影响行数；0 表示已有终态
     */
    public int markVoidIfIssued(String id, LocalDate voidDate) {
        return jdbc.update("""
                        UPDATE harvest_license
                           SET status = 'VOID', void_date = ?
                         WHERE id = ? AND status = 'ISSUED'
                        """,
                Date.valueOf(voidDate), id);
    }

    /**
     * 列出某小班当前有效证（用于“同时刻只允许一张有效证”的二次校验）。
     */
    public List<String> findActiveLicenseIds(String subplotId) {
        return jdbc.queryForList(
                "SELECT id FROM harvest_license WHERE subplot_id = ? AND status = 'ISSUED'",
                String.class, subplotId);
    }

    /**
     * 锁定所有已过期但仍为 ISSUED 的证件（expire_date &lt; today），按 ID 排序加锁，
     * 供每日扫描与采伐确认竞争唯一终态。
     */
    public List<LicensePO> findIssuedExpiredForUpdate(LocalDate today) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM harvest_license"
                        + " WHERE status = 'ISSUED' AND expire_date < ? ORDER BY id FOR UPDATE",
                (rs, n) -> mapRow(rs), Date.valueOf(today));
    }
}
