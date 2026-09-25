package com.example.starter.ride.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 空载试运行记录仓储；(设备, 日期, 次序) 唯一约束防并发重复。
 */
@Repository
public class TrialRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public TrialRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record TrialRow(int attemptSeq, boolean qualified) {
    }

    /**
     * 插入一次试运行记录；唯一约束冲突由 Spring 转换为 DuplicateKeyException。
     */
    public void insert(String requestId, String rideId, LocalDate bizDate, int attemptSeq,
                       boolean pressureBarOk, boolean seatBeltOk, boolean interlockOk,
                       boolean noAnomaly, boolean qualified, Instant createdAt) {
        jdbcTemplate.update(
                "INSERT INTO trial_run (request_id, ride_id, biz_date, attempt_seq, pressure_bar_ok, "
                        + "seat_belt_ok, interlock_ok, no_anomaly, qualified, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, rideId, bizDate, attemptSeq, pressureBarOk, seatBeltOk, interlockOk,
                noAnomaly, qualified, Timestamp.from(createdAt));
    }

    public int countQualified(String rideId, LocalDate bizDate) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trial_run WHERE ride_id = ? AND biz_date = ? AND qualified = TRUE",
                Integer.class, rideId, bizDate);
        return count == null ? 0 : count;
    }

    public long countTotalAttempts(String rideId, LocalDate bizDate) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trial_run WHERE ride_id = ? AND biz_date = ?",
                Long.class, rideId, bizDate);
        return count == null ? 0L : count;
    }

    /** 返回当日所有试运行记录（含不合格尝试），按次序升序，供复算。 */
    public List<TrialDetailRow> findDetails(String rideId, LocalDate bizDate) {
        return jdbcTemplate.query(
                "SELECT attempt_seq, pressure_bar_ok, seat_belt_ok, interlock_ok, no_anomaly, qualified, created_at "
                        + "FROM trial_run WHERE ride_id = ? AND biz_date = ? ORDER BY attempt_seq",
                (rs, n) -> new TrialDetailRow(rs.getInt("attempt_seq"),
                        rs.getBoolean("pressure_bar_ok"), rs.getBoolean("seat_belt_ok"),
                        rs.getBoolean("interlock_ok"), rs.getBoolean("no_anomaly"),
                        rs.getBoolean("qualified"), rs.getTimestamp("created_at").toInstant()),
                rideId, bizDate);
    }

    public record TrialDetailRow(int attemptSeq, boolean pressureBarOk, boolean seatBeltOk,
                                 boolean interlockOk, boolean noAnomaly, boolean qualified, Instant createdAt) {
    }
}
