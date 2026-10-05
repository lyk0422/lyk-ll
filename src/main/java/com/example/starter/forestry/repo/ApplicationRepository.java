package com.example.starter.forestry.repo;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.example.starter.forestry.po.ApplicationPO;

/**
 * 采伐申请单仓储。
 */
@Repository
public class ApplicationRepository {

    private final JdbcTemplate jdbc;

    public ApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLUMNS = """
            id, request_id, operator_id, subplot_id, species_code, quota_year, harvest_type,
            tree_count, volume, designed_volume, correction_count,
            status, license_id, reject_reason, archived, created_date, reviewed_date, archived_date
            """;

    private ApplicationPO mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ApplicationPO(
                rs.getString("id"),
                rs.getString("request_id"),
                rs.getString("operator_id"),
                rs.getString("subplot_id"),
                rs.getString("species_code"),
                rs.getInt("quota_year"),
                rs.getString("harvest_type"),
                rs.getInt("tree_count"),
                rs.getBigDecimal("volume"),
                rs.getBigDecimal("designed_volume"),
                rs.getInt("correction_count"),
                rs.getString("status"),
                rs.getString("license_id"),
                rs.getString("reject_reason"),
                rs.getBoolean("archived"),
                rs.getDate("created_date").toLocalDate(),
                rs.getDate("reviewed_date") == null ? null : rs.getDate("reviewed_date").toLocalDate(),
                rs.getDate("archived_date") == null ? null : rs.getDate("archived_date").toLocalDate());
    }

    public void insert(ApplicationPO po) {
        jdbc.update("""
                        INSERT INTO harvest_application
                            (id, request_id, operator_id, subplot_id, species_code, quota_year, harvest_type,
                             tree_count, volume, designed_volume, correction_count,
                             status, license_id, reject_reason, archived, created_date, reviewed_date, archived_date)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                po.id(), po.requestId(), po.operatorId(), po.subplotId(), po.speciesCode(), po.quotaYear(),
                po.harvestType(), po.treeCount(), po.volume(), po.designedVolume(), po.correctionCount(),
                po.status(), po.licenseId(), po.rejectReason(),
                po.archived(), Date.valueOf(po.createdDate()),
                po.reviewedDate() == null ? null : Date.valueOf(po.reviewedDate()),
                po.archivedDate() == null ? null : Date.valueOf(po.archivedDate()));
    }

    public ApplicationPO findById(String id) {
        List<ApplicationPO> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM harvest_application WHERE id = ?", (rs, n) -> mapRow(rs), id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ApplicationPO findByRequestId(String requestId) {
        List<ApplicationPO> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM harvest_application WHERE request_id = ?", (rs, n) -> mapRow(rs), requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void review(String id, String status, String rejectReason, LocalDate reviewedDate) {
        jdbc.update("""
                        UPDATE harvest_application
                           SET status = ?, reject_reason = ?, reviewed_date = ?
                         WHERE id = ?
                        """,
                status, rejectReason, Date.valueOf(reviewedDate), id);
    }

    /**
     * 设计值补正落库：更新株数、复核口径蓄积、设计值与目标状态，补正次数置 1。
     * 仅当当前状态为 CORRECTION_REQUIRED 时生效，保证只允许一次补正。
     *
     * @return 受影响行数；0 表示申请不在待补正状态
     */
    public int applyCorrection(String id, int treeCount, BigDecimal checkedVolume,
                               BigDecimal designedVolume, String status) {
        return jdbc.update("""
                        UPDATE harvest_application
                           SET tree_count = ?, volume = ?, designed_volume = ?,
                               correction_count = 1, status = ?
                         WHERE id = ? AND status = 'CORRECTION_REQUIRED' AND correction_count = 0
                        """,
                treeCount, checkedVolume, designedVolume, status, id);
    }

    public void attachLicense(String id, String licenseId, String status) {
        jdbc.update("UPDATE harvest_application SET license_id = ?, status = ? WHERE id = ?",
                licenseId, status, id);
    }

    public void updateStatus(String id, String status) {
        jdbc.update("UPDATE harvest_application SET status = ? WHERE id = ?", status, id);
    }

    public void archive(String id, LocalDate archivedDate) {
        jdbc.update("UPDATE harvest_application SET archived = 1, status = 'ARCHIVED', archived_date = ? WHERE id = ?",
                Date.valueOf(archivedDate), id);
    }

    /**
     * 统计小班当前有效采伐证（ISSUED 状态）数量，用于“同时刻只允许一张有效证”约束。
     */
    public int countActiveLicensesOnSubplot(String subplotId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM harvest_license WHERE subplot_id = ? AND status = 'ISSUED'",
                Integer.class, subplotId);
        return n == null ? 0 : n;
    }
}
