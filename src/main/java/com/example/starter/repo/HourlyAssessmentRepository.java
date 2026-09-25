package com.example.starter.repo;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.domain.HourlyAssessment;

/**
 * 小时评估结果访问，按 (plan_id, hour_start) 幂等 upsert。
 */
@Repository
public class HourlyAssessmentRepository {

    private static final RowMapper<HourlyAssessment> MAPPER = (ResultSet rs, int n) -> new HourlyAssessment(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getObject("hour_start", LocalDateTime.class),
            rs.getBigDecimal("nox_avg"),
            rs.getBigDecimal("so2_avg"),
            rs.getBigDecimal("dust_avg"),
            rs.getInt("reading_count"),
            rs.getBoolean("exceeded"),
            rs.getString("pollutants"),
            rs.getInt("consecutive_no"));

    private final JdbcTemplate jdbc;

    public HourlyAssessmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 幂等写入小时评估（乱序读数归位后可重算覆盖）。
     */
    public void upsert(HourlyAssessment a) {
        int updated = jdbc.update("""
                UPDATE hourly_assessment
                SET nox_avg = ?, so2_avg = ?, dust_avg = ?, reading_count = ?,
                    exceeded = ?, pollutants = ?, consecutive_no = ?, updated_at = CURRENT_TIMESTAMP
                WHERE plan_id = ? AND hour_start = ?
                """,
                a.noxAvg(), a.so2Avg(), a.dustAvg(), a.readingCount(),
                a.exceeded(), a.pollutants(), a.consecutiveNo(),
                a.planId(), Timestamp.valueOf(a.hourStart()));
        if (updated == 0) {
            try {
                jdbc.update("""
                        INSERT INTO hourly_assessment
                            (plan_id, hour_start, nox_avg, so2_avg, dust_avg, reading_count,
                             exceeded, pollutants, consecutive_no)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        a.planId(), Timestamp.valueOf(a.hourStart()),
                        a.noxAvg(), a.so2Avg(), a.dustAvg(), a.readingCount(),
                        a.exceeded(), a.pollutants(), a.consecutiveNo());
            } catch (DuplicateKeyException concurrent) {
                // 并发重算：改用更新覆盖
                jdbc.update("""
                        UPDATE hourly_assessment
                        SET nox_avg = ?, so2_avg = ?, dust_avg = ?, reading_count = ?,
                            exceeded = ?, pollutants = ?, consecutive_no = ?, updated_at = CURRENT_TIMESTAMP
                        WHERE plan_id = ? AND hour_start = ?
                        """,
                        a.noxAvg(), a.so2Avg(), a.dustAvg(), a.readingCount(),
                        a.exceeded(), a.pollutants(), a.consecutiveNo(),
                        a.planId(), Timestamp.valueOf(a.hourStart()));
            }
        }
    }

    /** 取方案截至指定小时（含）、按小时排序的评估序列，用于连续超标计数。 */
    public List<HourlyAssessment> findByPlanUpTo(long planId, LocalDateTime hourStart) {
        return jdbc.query("""
                SELECT id, plan_id, hour_start, nox_avg, so2_avg, dust_avg, reading_count,
                       exceeded, pollutants, consecutive_no
                FROM hourly_assessment
                WHERE plan_id = ? AND hour_start <= ?
                ORDER BY hour_start
                """, MAPPER, planId, Timestamp.valueOf(hourStart));
    }

    public List<HourlyAssessment> findByPlanAndDate(long planId, java.time.LocalDate date) {
        return jdbc.query("""
                SELECT id, plan_id, hour_start, nox_avg, so2_avg, dust_avg, reading_count,
                       exceeded, pollutants, consecutive_no
                FROM hourly_assessment
                WHERE plan_id = ? AND hour_start >= ? AND hour_start < ?
                ORDER BY hour_start
                """, MAPPER, planId,
                Timestamp.valueOf(date.atStartOfDay()),
                Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
    }
}
