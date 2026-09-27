package com.example.starter.cosmetic.child.persistence;

import com.example.starter.cosmetic.child.domain.AgeTier;
import com.example.starter.cosmetic.child.domain.ChildSafetyReport;
import com.example.starter.cosmetic.child.port.ChildSafetyReportPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的儿童安全评价报告适配器（合成报告登记/查询）。
 */
@Repository
public class JdbcChildSafetyReportPort implements ChildSafetyReportPort {

    private static final RowMapper<ChildSafetyReport> MAPPER = (rs, n) -> new ChildSafetyReport(
            rs.getString("report_no"),
            AgeTier.valueOf(rs.getString("tier_code")),
            rs.getDate("issued_date") == null ? null : rs.getDate("issued_date").toLocalDate(),
            rs.getInt("covers_from_months"),
            rs.getInt("covers_to_months"),
            rs.getInt("conclusion_supported") == 1,
            rs.getString("conclusion_text"));

    private final JdbcTemplate jdbc;

    public JdbcChildSafetyReportPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(ChildSafetyReport report) {
        jdbc.update("""
                MERGE INTO child_safety_report
                  (report_no, tier_code, issued_date, covers_from_months, covers_to_months,
                   conclusion_supported, conclusion_text, created_at_utc)
                KEY (report_no) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                report.reportNo(), report.tier().name(), report.issuedDate(),
                report.coversFromMonths(), report.coversToMonths(),
                report.conclusionSupported() ? 1 : 0, report.conclusionText(),
                Timestamp.from(Instant.now()));
    }

    @Override
    public Optional<ChildSafetyReport> findByNo(String reportNo) {
        return jdbc.query(
                "SELECT report_no, tier_code, issued_date, covers_from_months, covers_to_months, "
                        + "conclusion_supported, conclusion_text FROM child_safety_report WHERE report_no = ?",
                MAPPER, reportNo).stream().findFirst();
    }
}
