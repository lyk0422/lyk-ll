package com.example.starter.cosmetic.persistence;

import com.example.starter.cosmetic.domain.TestReportRecord;
import com.example.starter.cosmetic.port.ReportPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的功效评价试验报告适配器（合成报告登记/查询）。
 */
@Repository
public class JdbcReportPort implements ReportPort {

    private static final RowMapper<TestReportRecord> MAPPER = (rs, n) -> new TestReportRecord(
            rs.getString("report_no"),
            rs.getString("efficacy_code"),
            rs.getDate("issued_date").toLocalDate(),
            rs.getInt("conclusion_supported") == 1,
            rs.getString("conclusion_text"));

    private final JdbcTemplate jdbc;

    public JdbcReportPort(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsert(TestReportRecord report) {
        Instant now = Instant.now();
        jdbc.update("""
                MERGE INTO test_report
                  (report_no, efficacy_code, issued_date, conclusion_supported,
                   conclusion_text, created_at_utc)
                KEY (report_no) VALUES (?, ?, ?, ?, ?, ?)
                """,
                report.reportNo(), report.efficacyCode(), report.issuedDate(),
                report.conclusionSupported() ? 1 : 0, report.conclusionText(),
                java.sql.Timestamp.from(now));
    }

    @Override
    public Optional<TestReportRecord> findByNo(String reportNo) {
        return jdbc.query(
                "SELECT report_no, efficacy_code, issued_date, conclusion_supported, conclusion_text "
                        + "FROM test_report WHERE report_no = ?",
                MAPPER, reportNo).stream().findFirst();
    }
}
