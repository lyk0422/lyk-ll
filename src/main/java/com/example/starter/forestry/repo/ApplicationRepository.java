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
            tree_count, design_volume, checked_volume, correction_used,
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
                rs.getBigDecimal("design_volume"),
                rs.getBigDecimal("checked_volume"),
                rs.getBoolean("correction_used"),
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
                             tree_count, design_volume, checked_volume, correction_used,
                             status, license_id, reject_reason, archived, created_date, reviewed_date, archived_date)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                po.id(), po.requestId(), po.operatorId(), po.subplotId(), po.speciesCode(), po.quotaYear(),
                po.harvestType(), po.treeCount(), po.designVolume(), po.checkedVolume(), po.correctionUsed(),
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
     * 完成蓄积设计值补正：记录补正后设计值、标记补正机会已用并迁移状态
     * （补正一致转 SUBMITTED，仍不一致转 WITHDRAWN）。
     */
    public void completeCorrection(String id, String status, BigDecimal correctedDesignVolume) {
        jdbc.update("""
                        UPDATE harvest_application
                           SET status = ?, design_volume = ?, correction_used = 1
                         WHERE id = ?
                        """,
                status, correctedDesignVolume, id);
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
