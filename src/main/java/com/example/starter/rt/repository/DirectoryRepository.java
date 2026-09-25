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
 * 合成目录数据仓储：患者、危及器官约束模板与模板明细。
 */
@Repository
public class DirectoryRepository {

    private final JdbcTemplate jdbc;

    public DirectoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 约束模板明细行。 */
    public record ConstraintItemRow(
            Long id,
            String templateCode,
            String organCode,
            String organName,
            String metricType,
            Long metricRef,
            BigDecimal limitValue) {
    }

    /** 插入合成患者，已存在同 ID 时返回 false。 */
    public boolean insertPatient(String patientId, String patientName, LocalDateTime now) {
        int rows = jdbc.update(
                "INSERT INTO patient (patient_id, patient_name, created_at) VALUES (?, ?, ?)",
                patientId, patientName, now);
        return rows == 1;
    }

    public boolean patientExists(String patientId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM patient WHERE patient_id = ?", Integer.class, patientId);
        return count != null && count > 0;
    }

    /** 插入约束模板主记录，已存在同编码时返回 false。 */
    public boolean insertTemplate(String templateCode, String templateName, LocalDateTime now) {
        int rows = jdbc.update(
                "INSERT INTO constraint_template (template_code, template_name, created_at) VALUES (?, ?, ?)",
                templateCode, templateName, now);
        return rows == 1;
    }

    public boolean templateExists(String templateCode) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM constraint_template WHERE template_code = ?",
                Integer.class, templateCode);
        return count != null && count > 0;
    }

    public void insertConstraintItem(String templateCode, String organCode, String organName,
                                     String metricType, Long metricRef, BigDecimal limitValue) {
        jdbc.update("""
                INSERT INTO constraint_item
                    (template_code, organ_code, organ_name, metric_type, metric_ref, limit_value)
                VALUES (?, ?, ?, ?, ?, ?)
                """, templateCode, organCode, organName, metricType, metricRef, limitValue);
    }

    public List<ConstraintItemRow> findConstraintItems(String templateCode) {
        return jdbc.query("""
                SELECT id, template_code, organ_code, organ_name, metric_type, metric_ref, limit_value
                FROM constraint_item
                WHERE template_code = ?
                ORDER BY id
                """, new ConstraintItemRowMapper(), templateCode);
    }

    private static final class ConstraintItemRowMapper implements RowMapper<ConstraintItemRow> {
        @Override
        public ConstraintItemRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            Long metricRef = (Long) rs.getObject("metric_ref");
            return new ConstraintItemRow(
                    rs.getLong("id"),
                    rs.getString("template_code"),
                    rs.getString("organ_code"),
                    rs.getString("organ_name"),
                    rs.getString("metric_type"),
                    metricRef,
                    rs.getBigDecimal("limit_value"));
        }
    }

    public Optional<String> findPatientName(String patientId) {
        List<String> names = jdbc.query(
                "SELECT patient_name FROM patient WHERE patient_id = ?",
                (rs, rowNum) -> rs.getString(1), patientId);
        return names.stream().findFirst();
    }
}
