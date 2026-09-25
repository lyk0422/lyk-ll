package com.example.starter.trial.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 受试者访视仓储（合成数据）。
 */
@Repository
public class VisitRepository {

    private final JdbcTemplate jdbcTemplate;

    public VisitRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record VisitRow(
            String trialCode,
            String screeningNo,
            String visitCode,
            Instant visitTimeUtc,
            boolean excludedFromMainAnalysis) {
    }

    public void insert(String trialCode, String screeningNo, String visitCode,
                       Instant visitTimeUtc, boolean excluded) {
        jdbcTemplate.update(
                "INSERT INTO subject_visit(trial_code, screening_no, visit_code, visit_time_utc, "
                        + "excluded_from_main_analysis, recorded_at_utc) VALUES (?, ?, ?, ?, ?, ?)",
                trialCode, screeningNo, visitCode, Timestamp.from(visitTimeUtc), excluded,
                Timestamp.from(visitTimeUtc));
    }

    /**
     * 揭盲生效时：将访视 UTC 日期不早于揭盲 UTC 日期的访视全部排除出主要分析集（终态，不可回退）。
     */
    public int markExcludedOnOrAfterUtcDay(String trialCode, String screeningNo,
                                           java.time.LocalDate unblindUtcDate) {
        return jdbcTemplate.update(
                "UPDATE subject_visit SET excluded_from_main_analysis = TRUE "
                        + "WHERE trial_code = ? AND screening_no = ? "
                        + "AND CAST(visit_time_utc AS DATE) >= ? AND excluded_from_main_analysis = FALSE",
                trialCode, screeningNo, java.sql.Date.valueOf(unblindUtcDate));
    }

    public List<VisitRow> list(String trialCode, String screeningNo) {
        return jdbcTemplate.query(
                "SELECT trial_code, screening_no, visit_code, visit_time_utc, excluded_from_main_analysis "
                        + "FROM subject_visit WHERE trial_code = ? AND screening_no = ? "
                        + "ORDER BY visit_time_utc, id",
                (rs, n) -> new VisitRow(
                        rs.getString("trial_code"),
                        rs.getString("screening_no"),
                        rs.getString("visit_code"),
                        rs.getTimestamp("visit_time_utc").toInstant(),
                        rs.getBoolean("excluded_from_main_analysis")),
                trialCode, screeningNo);
    }
}
