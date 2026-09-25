package com.example.starter.repo;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 日结记录访问。超标时段与降掺记录以 JSON 字符串落库，封存为终态。
 */
@Repository
public class DailySummaryRepository {

    /**
     * 日结持久化视图。
     */
    public record DailySummaryRow(
            long planId,
            LocalDate summaryDate,
            BigDecimal actualSubstitutionRate,
            String exceededPeriodsJson,
            String reductionRecordsJson,
            String effectiveHoursJson,
            boolean sealed,
            LocalDateTime sealedAt) {
    }

    private final JdbcTemplate jdbc;

    public DailySummaryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DailySummaryRow find(long planId, LocalDate date) {
        List<DailySummaryRow> list = jdbc.query("""
                SELECT plan_id, summary_date, actual_substitution_rate, exceeded_periods,
                       reduction_records, effective_hours, sealed, sealed_at
                FROM daily_summary WHERE plan_id = ? AND summary_date = ?
                """, (rs, n) -> new DailySummaryRow(
                rs.getLong("plan_id"),
                rs.getObject("summary_date", LocalDate.class),
                rs.getBigDecimal("actual_substitution_rate"),
                rs.getString("exceeded_periods"),
                rs.getString("reduction_records"),
                rs.getString("effective_hours"),
                rs.getBoolean("sealed"),
                rs.getObject("sealed_at", LocalDateTime.class)), planId, Date.valueOf(date));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 幂等 upsert 日结（未封存时可重算覆盖；封存后服务层拒绝覆盖）。 */
    public void upsert(DailySummaryRow row) {
        int updated = jdbc.update("""
                UPDATE daily_summary
                SET actual_substitution_rate = ?, exceeded_periods = ?,
                    reduction_records = ?, effective_hours = ?, sealed = ?, sealed_at = ?
                WHERE plan_id = ? AND summary_date = ?
                """,
                row.actualSubstitutionRate(),
                row.exceededPeriodsJson(), row.reductionRecordsJson(), row.effectiveHoursJson(),
                row.sealed(),
                row.sealedAt() == null ? null : Timestamp.valueOf(row.sealedAt()),
                row.planId(), Date.valueOf(row.summaryDate()));
        if (updated == 0) {
            try {
                jdbc.update("""
                        INSERT INTO daily_summary
                            (plan_id, summary_date, actual_substitution_rate, exceeded_periods,
                             reduction_records, effective_hours, sealed, sealed_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        row.planId(), Date.valueOf(row.summaryDate()),
                        row.actualSubstitutionRate(),
                        row.exceededPeriodsJson(), row.reductionRecordsJson(),
                        row.effectiveHoursJson(), row.sealed(),
                        row.sealedAt() == null ? null : Timestamp.valueOf(row.sealedAt()));
            } catch (org.springframework.dao.DuplicateKeyException concurrent) {
                jdbc.update("""
                        UPDATE daily_summary
                        SET actual_substitution_rate = ?, exceeded_periods = ?,
                            reduction_records = ?, effective_hours = ?, sealed = ?, sealed_at = ?
                        WHERE plan_id = ? AND summary_date = ?
                        """,
                        row.actualSubstitutionRate(),
                        row.exceededPeriodsJson(), row.reductionRecordsJson(),
                        row.effectiveHoursJson(), row.sealed(),
                        row.sealedAt() == null ? null : Timestamp.valueOf(row.sealedAt()),
                        row.planId(), Date.valueOf(row.summaryDate()));
            }
        }
    }

    public int markSealed(long planId, LocalDate date, LocalDateTime sealedAt) {
        return jdbc.update("""
                UPDATE daily_summary SET sealed = TRUE, sealed_at = ?
                WHERE plan_id = ? AND summary_date = ?
                """, Timestamp.valueOf(sealedAt), planId, Date.valueOf(date));
    }
}
