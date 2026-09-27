package com.example.starter.cosmetic.persistence;

import com.example.starter.cosmetic.domain.ChildrenSafetyReport;
import com.example.starter.cosmetic.port.ChildrenReportPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的儿童安全报告适配器（合成报告登记/查询）。
 */
@Repository
public class JdbcChildrenReportPort implements ChildrenReportPort {

    private static final RowMapper<ChildrenSafetyReport> MAPPER = (rs, n) -> new ChildrenSafetyReport(
            rs.getString("report_no"),
            rs.getString("band_code"),
            rs.getInt("coverage_lower_months"),
            rs.getInt("coverage_upper_months"),
            rs.getDate("issued_date").toLocalDate(),
            rs.getInt("conclusion_supported") == 1,
            rs.getString("conclusion_text"));

    private final JdbcTemplate jdbc;

    public JdbcChildrenReportPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(ChildrenSafetyReport report) {
        jdbc.update("""
                MERGE INTO children_safety_report
                  (report_no, band_code, coverage_lower_months, coverage_upper_months,
                   issued_date, conclusion_supported, conclusion_text, created_at_utc)
                KEY (report_no) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                report.reportNo(), report.bandCode(),
                report.coverageLowerMonths(), report.coverageUpperMonths(),
                java.sql.Date.valueOf(report.issuedDate()),
                report.conclusionSupported() ? 1 : 0, report.conclusionText(),
                java.sql.Timestamp.from(Instant.now()));
    }

    @Override
    public Optional<ChildrenSafetyReport> findByNo(String reportNo) {
        return jdbc.query(
                "SELECT report_no, band_code, coverage_lower_months, coverage_upper_months, "
                        + "issued_date, conclusion_supported, conclusion_text "
                        + "FROM children_safety_report WHERE report_no = ?",
                MAPPER, reportNo).stream().findFirst();
    }
}
