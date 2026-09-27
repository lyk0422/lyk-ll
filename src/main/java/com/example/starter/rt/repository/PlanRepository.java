package com.example.starter.rt.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 计划版本与申报剂量仓储，含患者“唯一在执行计划”守卫表。
 */
@Repository
public class PlanRepository {

    private final JdbcTemplate jdbc;

    public PlanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** plan_version 行。 */
    public record PlanVersionRow(
            Long id,
            String patientId,
            String planCode,
            Integer versionNo,
            String templateCode,
            Long prescribedTotalDoseCgy,
            Long fractionDoseCgy,
            Integer fractionCount,
            String status,
            LocalDateTime createdAt,
            LocalDateTime reviewedAt,
            LocalDateTime approvedAt,
            LocalDateTime verifiedAt,
            LocalDateTime firstFractionAt,
            LocalDateTime closedAt,
            LocalDateTime invalidatedAt) {
    }

    /** 计划申报 OAR 剂量行。 */
    public record OarDoseRow(
            Long id,
            Long planVersionId,
            String organCode,
            String metricType,
            Long metricRef,
            BigDecimal metricValue) {
    }

    /** 患者在执行计划守卫行。 */
    public record ActivePlanRow(
            String patientId,
            String planCode,
            Long planVersionId,
            String status,
            LocalDateTime updatedAt) {
    }

    private static final class PlanVersionRowMapper implements RowMapper<PlanVersionRow> {
        @Override
        public PlanVersionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new PlanVersionRow(
                    rs.getLong("id"),
                    rs.getString("patient_id"),
                    rs.getString("plan_code"),
                    rs.getInt("version_no"),
                    rs.getString("template_code"),
                    rs.getLong("prescribed_total_dose_cgy"),
                    rs.getLong("fraction_dose_cgy"),
                    rs.getInt("fraction_count"),
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toLocalDateTime(),
                    toLdt(rs, "reviewed_at"),
                    toLdt(rs, "approved_at"),
                    toLdt(rs, "verified_at"),
                    toLdt(rs, "first_fraction_at"),
                    toLdt(rs, "closed_at"),
                    toLdt(rs, "invalidated_at"));
        }

        private LocalDateTime toLdt(ResultSet rs, String column) throws SQLException {
            return rs.getTimestamp(column) == null ? null : rs.getTimestamp(column).toLocalDateTime();
        }
    }

    private static final class OarDoseRowMapper implements RowMapper<OarDoseRow> {
        @Override
        public OarDoseRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long metricRef = (Long) rs.getObject("metric_ref");
            return new OarDoseRow(
                    rs.getLong("id"),
                    rs.getLong("plan_version_id"),
                    rs.getString("organ_code"),
                    rs.getString("metric_type"),
                    metricRef,
                    rs.getBigDecimal("metric_value"));
        }
    }

    /**
     * 插入新版本，version_no 由同患者+计划当前最大版本号 +1 派生。
     *
     * @return 新版本ID
     */
    public long insertVersion(String patientId, String planCode, int versionNo, String templateCode,
                              long prescribedTotalDoseCgy, long fractionDoseCgy, int fractionCount,
                              LocalDateTime now) {
        jdbc.update("""
                INSERT INTO plan_version
                    (patient_id, plan_code, version_no, template_code,
                     prescribed_total_dose_cgy, fraction_dose_cgy, fraction_count,
                     status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'DESIGN', ?)
                """, patientId, planCode, versionNo, templateCode,
                prescribedTotalDoseCgy, fractionDoseCgy, fractionCount, now);
        return jdbc.queryForObject(
                "SELECT id FROM plan_version WHERE patient_id = ? AND plan_code = ? AND version_no = ?",
                Long.class, patientId, planCode, versionNo);
    }

    /** 同患者+计划下一个版本号（无历史版本时为 1）。 */
    public int nextVersionNo(String patientId, String planCode) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM plan_version WHERE patient_id = ? AND plan_code = ?",
                Integer.class, patientId, planCode);
        return (max == null ? 0 : max) + 1;
    }

    public void insertOarDose(long planVersionId, String organCode, String metricType,
                              Long metricRef, BigDecimal metricValue) {
        jdbc.update("""
                INSERT INTO plan_oar_dose
                    (plan_version_id, organ_code, metric_type, metric_ref, metric_value)
                VALUES (?, ?, ?, ?, ?)
                """, planVersionId, organCode, metricType, metricRef, metricValue);
    }

    public List<OarDoseRow> findOarDoses(long planVersionId) {
        return jdbc.query("""
                SELECT id, plan_version_id, organ_code, metric_type, metric_ref, metric_value
                FROM plan_oar_dose
                WHERE plan_version_id = ?
                ORDER BY id
                """, new OarDoseRowMapper(), planVersionId);
    }

    public Optional<PlanVersionRow> findVersion(String patientId, String planCode, int versionNo) {
        List<PlanVersionRow> rows = jdbc.query("""
                SELECT id, patient_id, plan_code, version_no, template_code,
                       prescribed_total_dose_cgy, fraction_dose_cgy, fraction_count, status,
                       created_at, reviewed_at, approved_at, verified_at,
                       first_fraction_at, closed_at, invalidated_at
                FROM plan_version
                WHERE patient_id = ? AND plan_code = ? AND version_no = ?
                """, new PlanVersionRowMapper(), patientId, planCode, versionNo);
        return rows.stream().findFirst();
    }

    /**
     * 锁定指定计划版本行（SELECT ... FOR UPDATE），串行化同版本审核/验证与同键并发请求。
     * 行不存在时返回空。
     */
    public Optional<PlanVersionRow> lockVersion(String patientId, String planCode, int versionNo) {
        List<PlanVersionRow> rows = jdbc.query("""
                SELECT id, patient_id, plan_code, version_no, template_code,
                       prescribed_total_dose_cgy, fraction_dose_cgy, fraction_count, status,
                       created_at, reviewed_at, approved_at, verified_at,
                       first_fraction_at, closed_at, invalidated_at
                FROM plan_version
                WHERE patient_id = ? AND plan_code = ? AND version_no = ?
                FOR UPDATE
                """, new PlanVersionRowMapper(), patientId, planCode, versionNo);
        return rows.stream().findFirst();
    }

    public Optional<PlanVersionRow> findVersionById(long planVersionId) {
        List<PlanVersionRow> rows = jdbc.query("""
                SELECT id, patient_id, plan_code, version_no, template_code,
                       prescribed_total_dose_cgy, fraction_dose_cgy, fraction_count, status,
                       created_at, reviewed_at, approved_at, verified_at,
                       first_fraction_at, closed_at, invalidated_at
                FROM plan_version
                WHERE id = ?
                """, new PlanVersionRowMapper(), planVersionId);
        return rows.stream().findFirst();
    }

    /** 按主键锁定计划版本行（SELECT ... FOR UPDATE）。 */
    public Optional<PlanVersionRow> lockVersionById(long planVersionId) {
        List<PlanVersionRow> rows = jdbc.query("""
                SELECT id, patient_id, plan_code, version_no, template_code,
                       prescribed_total_dose_cgy, fraction_dose_cgy, fraction_count, status,
                       created_at, reviewed_at, approved_at, verified_at,
                       first_fraction_at, closed_at, invalidated_at
                FROM plan_version
                WHERE id = ?
                FOR UPDATE
                """, new PlanVersionRowMapper(), planVersionId);
        return rows.stream().findFirst();
    }

    /** 同患者+计划全部版本，按版本号升序。 */
    public List<PlanVersionRow> findVersions(String patientId, String planCode) {
        return jdbc.query("""
                SELECT id, patient_id, plan_code, version_no, template_code,
                       prescribed_total_dose_cgy, fraction_dose_cgy, fraction_count, status,
                       created_at, reviewed_at, approved_at, verified_at,
                       first_fraction_at, closed_at, invalidated_at
                FROM plan_version
                WHERE patient_id = ? AND plan_code = ?
                ORDER BY version_no
                """, new PlanVersionRowMapper(), patientId, planCode);
    }

    public int updateStatus(long planVersionId, String expectedStatus, String newStatus) {
        return jdbc.update(
                "UPDATE plan_version SET status = ? WHERE id = ? AND status = ?",
                newStatus, planVersionId, expectedStatus);
    }

    public void markReviewed(long planVersionId, LocalDateTime now) {
        jdbc.update(
                "UPDATE plan_version SET status = 'REVIEWED', reviewed_at = ? WHERE id = ?",
                now, planVersionId);
    }

    public void markApproved(long planVersionId, LocalDateTime now) {
        jdbc.update(
                "UPDATE plan_version SET status = 'APPROVED', approved_at = ? WHERE id = ?",
                now, planVersionId);
    }

    public void markVerified(long planVersionId, LocalDateTime now) {
        jdbc.update(
                "UPDATE plan_version SET status = 'VERIFIED', verified_at = ? WHERE id = ?",
                now, planVersionId);
    }

    public void markExecutingIfFirst(long planVersionId, LocalDateTime now) {
        jdbc.update("""
                UPDATE plan_version
                SET status = 'EXECUTING', first_fraction_at = COALESCE(first_fraction_at, ?)
                WHERE id = ?
                """, now, planVersionId);
    }

    public void markClosed(long planVersionId, LocalDateTime now) {
        jdbc.update(
                "UPDATE plan_version SET status = 'CLOSED', closed_at = ? WHERE id = ?",
                now, planVersionId);
    }

    /** 将指定版本以外的全部历史版本即刻置为 INVALID（换版同一事务调用）。 */
    public int invalidateOtherVersions(String patientId, String planCode, long activeVersionId,
                                       LocalDateTime now) {
        return jdbc.update("""
                UPDATE plan_version
                SET status = 'INVALID', invalidated_at = ?
                WHERE patient_id = ? AND plan_code = ? AND id <> ? AND status <> 'INVALID'
                """, now, patientId, planCode, activeVersionId);
    }

    /**
     * 占用患者唯一在执行计划守卫。已存在行（含 CLOSED 终态）时插入失败。
     *
     * @throws DuplicateKeyException 患者已有在执行/已结案计划
     */
    public void insertActivePlan(String patientId, String planCode, long planVersionId,
                                 LocalDateTime now) {
        jdbc.update("""
                INSERT INTO patient_active_plan
                    (patient_id, plan_code, plan_version_id, status, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', ?)
                """, patientId, planCode, planVersionId, now);
    }

    /** 换版成功后在同一事务内把守卫指向新版本。 */
    public int pointActivePlanTo(String patientId, String planCode, long planVersionId,
                                 LocalDateTime now) {
        return jdbc.update("""
                UPDATE patient_active_plan
                SET plan_code = ?, plan_version_id = ?, status = 'ACTIVE', updated_at = ?
                WHERE patient_id = ? AND status = 'ACTIVE'
                """, planCode, planVersionId, now, patientId);
    }

    public void closeActivePlan(String patientId, LocalDateTime now) {
        jdbc.update(
                "UPDATE patient_active_plan SET status = 'CLOSED', updated_at = ? WHERE patient_id = ?",
                now, patientId);
    }

    public Optional<ActivePlanRow> findActivePlan(String patientId) {
        List<ActivePlanRow> rows = jdbc.query("""
                SELECT patient_id, plan_code, plan_version_id, status, updated_at
                FROM patient_active_plan
                WHERE patient_id = ?
                """, (rs, n) -> new ActivePlanRow(
                rs.getString("patient_id"),
                rs.getString("plan_code"),
                rs.getLong("plan_version_id"),
                rs.getString("status"),
                rs.getTimestamp("updated_at").toLocalDateTime()), patientId);
        return rows.stream().findFirst();
    }

    /**
     * 对患者主记录加行级排他锁（SELECT ... FOR UPDATE），
     * 用于串行化同一患者的并发批准/结案，保证唯一在执行计划。
     *
     * @return 患者是否存在
     */
    public boolean lockPatient(String patientId) {
        List<String> locked = jdbc.query(
                "SELECT patient_id FROM patient WHERE patient_id = ? FOR UPDATE",
                (rs, n) -> rs.getString(1), patientId);
        return !locked.isEmpty();
    }

    /** 仅用于测试清理：清空全部业务表并重置自增。 */
    public void deleteAllForTest() {
        jdbc.execute("DELETE FROM idempotent_request");
        jdbc.execute("DELETE FROM schedule_item");
        jdbc.execute("DELETE FROM plan_schedule");
        jdbc.execute("DELETE FROM device");
        jdbc.execute("DELETE FROM late_fraction_event");
        jdbc.execute("DELETE FROM fraction_execution");
        jdbc.execute("DELETE FROM verification");
        jdbc.execute("DELETE FROM physics_audit_item");
        jdbc.execute("DELETE FROM physics_audit");
        jdbc.execute("DELETE FROM plan_oar_dose");
        jdbc.execute("DELETE FROM plan_version");
        jdbc.execute("DELETE FROM patient_active_plan");
        jdbc.execute("DELETE FROM constraint_item");
        jdbc.execute("DELETE FROM constraint_template");
        jdbc.execute("DELETE FROM patient");
        jdbc.execute("ALTER TABLE plan_version ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE plan_oar_dose ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE physics_audit ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE physics_audit_item ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE verification ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE fraction_execution ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE late_fraction_event ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE plan_schedule ALTER COLUMN id RESTART WITH 1");
        jdbc.execute("ALTER TABLE schedule_item ALTER COLUMN id RESTART WITH 1");
    }
}
