package com.example.starter.cosmetic.child.persistence;

import com.example.starter.cosmetic.child.domain.ChildSafetyReport;
import com.example.starter.cosmetic.child.port.ChildSafetyReportPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的儿童安全报告登记适配器。
 */
@Repository
public class JdbcChildSafetyReportPort implements ChildSafetyReportPort {

    private static final RowMapper<ChildSafetyReport> MAPPER = (rs, n) -> new ChildSafetyReport(
            rs.getString("report_no"),
            rs.getString("band_code"),
            rs.getInt("cover_lower_months"),
            rs.getInt("cover_upper_months"),
            rs.getString("conclusion_text"));

    private final JdbcTemplate jdbc;

    public JdbcChildSafetyReportPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(ChildSafetyReport report) {
        jdbc.update("""
                MERGE INTO child_safety_report
                  (report_no, band_code, cover_lower_months, cover_upper_months,
                   conclusion_text, created_at_utc)
                KEY (report_no) VALUES (?, ?, ?, ?, ?, ?)
                """,
                report.reportNo(), report.bandCode(), report.coverLowerMonths(),
                report.coverUpperMonths(), report.conclusionText(), Timestamp.from(Instant.now()));
    }

    @Override
    public Optional<ChildSafetyReport> findByNo(String reportNo) {
        return jdbc.query("""
                SELECT report_no, band_code, cover_lower_months, cover_upper_months, conclusion_text
                FROM child_safety_report WHERE report_no = ?
                """, MAPPER, reportNo).stream().findFirst();
    }
}
