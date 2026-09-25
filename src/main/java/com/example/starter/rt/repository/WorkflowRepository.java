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
 * 工作流记录仓储：物理审核明细、模体验证链、分次台账、结案后迟到事件与幂等记录。
 */
@Repository
public class WorkflowRepository {

    private final JdbcTemplate jdbc;

    public WorkflowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 物理审核主记录。 */
    public record AuditRow(
            Long id,
            Long planVersionId,
            String requestId,
            boolean totalDoseCheckOk,
            boolean passed,
            LocalDateTime auditedAt) {
    }

    /** 物理审核逐项快照。 */
    public record AuditItemRow(
            Long id,
            Long auditId,
            String organCode,
            String organName,
            String metricType,
            Long metricRef,
            BigDecimal plannedValue,
            BigDecimal limitValue,
            boolean withinLimit) {
    }

    /** 模体验证记录（成功失败均留痕）。 */
    public record VerificationRow(
            Long id,
            Long planVersionId,
            String requestId,
            long plannedDoseCgy,
            long measuredDoseCgy,
            BigDecimal deviationPct,
            boolean passed,
            LocalDateTime createdAt) {
    }

    /** 分次台账行。 */
    public record FractionRow(
            Long id,
            String patientId,
            String planCode,
            Long planVersionId,
            int fractionNo,
            long deliveredDoseCgy,
            String requestId,
            LocalDateTime executedAt) {
    }

    /** 迟到分次事件行。 */
    public record LateFractionRow(
            Long id,
            String patientId,
            String planCode,
            Long planVersionId,
            int fractionNo,
            String requestId,
            LocalDateTime createdAt) {
    }

    /** 幂等记录行。 */
    public record IdempotentRow(
            String requestId,
            String operation,
            String fingerprint,
            int httpStatus,
            String responseJson,
            LocalDateTime createdAt) {
    }

    public long insertAudit(long planVersionId, String requestId, boolean totalDoseCheckOk,
                            boolean passed, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO physics_audit
                    (plan_version_id, request_id, total_dose_check_ok, passed, audited_at)
                VALUES (?, ?, ?, ?, ?)
                """, planVersionId, requestId, totalDoseCheckOk, passed, now);
        Long id = jdbc.queryForObject(
                "SELECT id FROM physics_audit WHERE plan_version_id = ?", Long.class, planVersionId);
        return id == null ? 0L : id;
    }

    public void insertAuditItem(long auditId, String organCode, String organName, String metricType,
                                Long metricRef, BigDecimal plannedValue, BigDecimal limitValue,
                                boolean withinLimit) {
        jdbc.update("""
                INSERT INTO physics_audit_item
                    (audit_id, organ_code, organ_name, metric_type, metric_ref,
                     planned_value, limit_value, within_limit)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, auditId, organCode, organName, metricType, metricRef,
                plannedValue, limitValue, withinLimit);
    }

    public Optional<AuditRow> findAuditByVersion(long planVersionId) {
        List<AuditRow> rows = jdbc.query("""
                SELECT id, plan_version_id, request_id, total_dose_check_ok, passed, audited_at
                FROM physics_audit WHERE plan_version_id = ?
                """, new AuditRowMapper(), planVersionId);
        return rows.stream().findFirst();
    }

    public List<AuditItemRow> findAuditItems(long auditId) {
        return jdbc.query("""
                SELECT id, audit_id, organ_code, organ_name, metric_type, metric_ref,
                       planned_value, limit_value, within_limit
                FROM physics_audit_item WHERE audit_id = ? ORDER BY id
                """, new AuditItemRowMapper(), auditId);
    }

    public long insertVerification(long planVersionId, String requestId, long plannedDoseCgy,
                                   long measuredDoseCgy, BigDecimal deviationPct, boolean passed,
                                   LocalDateTime now) {
        jdbc.update("""
                INSERT INTO verification
                    (plan_version_id, request_id, planned_dose_cgy, measured_dose_cgy,
                     deviation_pct, passed, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, planVersionId, requestId, plannedDoseCgy, measuredDoseCgy,
                deviationPct, passed, now);
        Long id = jdbc.queryForObject(
                "SELECT id FROM verification WHERE request_id = ?", Long.class, requestId);
        return id == null ? 0L : id;
    }

    public List<VerificationRow> findVerifications(long planVersionId) {
        return jdbc.query("""
                SELECT id, plan_version_id, request_id, planned_dose_cgy, measured_dose_cgy,
                       deviation_pct, passed, created_at
                FROM verification WHERE plan_version_id = ? ORDER BY id
                """, new VerificationRowMapper(), planVersionId);
    }

    public long insertFraction(String patientId, String planCode, long planVersionId, int fractionNo,
                               long deliveredDoseCgy, String requestId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO fraction_execution
                    (patient_id, plan_code, plan_version_id, fraction_no,
                     delivered_dose_cgy, request_id, executed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, patientId, planCode, planVersionId, fractionNo,
                deliveredDoseCgy, requestId, now);
        Long id = jdbc.queryForObject("""
                SELECT id FROM fraction_execution
                WHERE patient_id = ? AND plan_code = ? AND fraction_no = ?
                """, Long.class, patientId, planCode, fractionNo);
        return id == null ? 0L : id;
    }

    public List<FractionRow> findFractions(String patientId, String planCode) {
        return jdbc.query("""
                SELECT id, patient_id, plan_code, plan_version_id, fraction_no,
                       delivered_dose_cgy, request_id, executed_at
                FROM fraction_execution
                WHERE patient_id = ? AND plan_code = ?
                ORDER BY fraction_no
                """, new FractionRowMapper(), patientId, planCode);
    }

    public Optional<FractionRow> findFraction(String patientId, String planCode, int fractionNo) {
        List<FractionRow> rows = jdbc.query("""
                SELECT id, patient_id, plan_code, plan_version_id, fraction_no,
                       delivered_dose_cgy, request_id, executed_at
                FROM fraction_execution
                WHERE patient_id = ? AND plan_code = ? AND fraction_no = ?
                """, new FractionRowMapper(), patientId, planCode, fractionNo);
        return rows.stream().findFirst();
    }

    public int countFractions(String patientId, String planCode) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(1) FROM fraction_execution
                WHERE patient_id = ? AND plan_code = ?
                """, Integer.class, patientId, planCode);
        return count == null ? 0 : count;
    }

    public void insertLateFraction(String patientId, String planCode, long planVersionId,
                                   int fractionNo, String requestId, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO late_fraction_event
                    (patient_id, plan_code, plan_version_id, fraction_no, request_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, patientId, planCode, planVersionId, fractionNo, requestId, now);
    }

    public List<LateFractionRow> findLateFractions(String patientId, String planCode) {
        return jdbc.query("""
                SELECT id, patient_id, plan_code, plan_version_id, fraction_no, request_id, created_at
                FROM late_fraction_event
                WHERE patient_id = ? AND plan_code = ?
                ORDER BY id
                """, (rs, n) -> new LateFractionRow(
                rs.getLong("id"),
                rs.getString("patient_id"),
                rs.getString("plan_code"),
                rs.getLong("plan_version_id"),
                rs.getInt("fraction_no"),
                rs.getString("request_id"),
                rs.getTimestamp("created_at").toLocalDateTime()), patientId, planCode);
    }

    /** 幂等记录插入；requestId 已存在时由唯一约束抛出 DuplicateKeyException。 */
    public void insertIdempotent(String requestId, String operation, String fingerprint,
                                 int httpStatus, String responseJson, LocalDateTime now) {
        jdbc.update("""
                INSERT INTO idempotent_request
                    (request_id, operation, fingerprint, http_status, response_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, requestId, operation, fingerprint, httpStatus, responseJson, now);
    }

    public Optional<IdempotentRow> findIdempotent(String requestId) {
        List<IdempotentRow> rows = jdbc.query("""
                SELECT request_id, operation, fingerprint, http_status, response_json, created_at
                FROM idempotent_request WHERE request_id = ?
                """, (rs, n) -> new IdempotentRow(
                rs.getString("request_id"),
                rs.getString("operation"),
                rs.getString("fingerprint"),
                rs.getInt("http_status"),
                rs.getString("response_json"),
                rs.getTimestamp("created_at").toLocalDateTime()), requestId);
        return rows.stream().findFirst();
    }

    private static final class AuditRowMapper implements RowMapper<AuditRow> {
        @Override
        public AuditRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new AuditRow(
                    rs.getLong("id"),
                    rs.getLong("plan_version_id"),
                    rs.getString("request_id"),
                    rs.getBoolean("total_dose_check_ok"),
                    rs.getBoolean("passed"),
                    rs.getTimestamp("audited_at").toLocalDateTime());
        }
    }

    private static final class AuditItemRowMapper implements RowMapper<AuditItemRow> {
        @Override
        public AuditItemRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long metricRef = (Long) rs.getObject("metric_ref");
            return new AuditItemRow(
                    rs.getLong("id"),
                    rs.getLong("audit_id"),
                    rs.getString("organ_code"),
                    rs.getString("organ_name"),
                    rs.getString("metric_type"),
                    metricRef,
                    rs.getBigDecimal("planned_value"),
                    rs.getBigDecimal("limit_value"),
                    rs.getBoolean("within_limit"));
        }
    }

    private static final class VerificationRowMapper implements RowMapper<VerificationRow> {
        @Override
        public VerificationRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new VerificationRow(
                    rs.getLong("id"),
                    rs.getLong("plan_version_id"),
                    rs.getString("request_id"),
                    rs.getLong("planned_dose_cgy"),
                    rs.getLong("measured_dose_cgy"),
                    rs.getBigDecimal("deviation_pct"),
                    rs.getBoolean("passed"),
                    rs.getTimestamp("created_at").toLocalDateTime());
        }
    }

    private static final class FractionRowMapper implements RowMapper<FractionRow> {
        @Override
        public FractionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new FractionRow(
                    rs.getLong("id"),
                    rs.getString("patient_id"),
                    rs.getString("plan_code"),
                    rs.getLong("plan_version_id"),
                    rs.getInt("fraction_no"),
                    rs.getLong("delivered_dose_cgy"),
                    rs.getString("request_id"),
                    rs.getTimestamp("executed_at").toLocalDateTime());
        }
    }
}
