package com.example.starter.trial.domain;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 揭盲事件、审计事件与授权人仓储。
 */
@Repository
public class UnblindingRepository {

    private final JdbcTemplate jdbcTemplate;

    public UnblindingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 揭盲事件行（终态，每名受试者至多一条）。
     */
    public record UnblindEventRow(
            long id,
            String trialCode,
            String screeningNo,
            String requestId,
            String status,
            String firstConfirmer,
            String secondConfirmer,
            Instant createdAtUtc,
            Instant effectiveAtUtc) {
    }

    private static final RowMapper<UnblindEventRow> EVENT_MAPPER = (rs, n) -> new UnblindEventRow(
            rs.getLong("id"),
            rs.getString("trial_code"),
            rs.getString("screening_no"),
            rs.getString("request_id"),
            rs.getString("status"),
            rs.getString("first_confirmer"),
            rs.getString("second_confirmer"),
            rs.getTimestamp("created_at_utc").toInstant(),
            rs.getTimestamp("effective_at_utc").toInstant());

    public boolean isAuthorized(String userCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM authorized_unblinder WHERE user_code = ?",
                Integer.class, userCode);
        return count != null && count > 0;
    }

    public Optional<UnblindEventRow> findByScreeningNo(String trialCode, String screeningNo) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT * FROM unblind_event WHERE trial_code = ? AND screening_no = ?",
                    EVENT_MAPPER, trialCode, screeningNo));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<UnblindEventRow> findByRequestId(String trialCode, String requestId) {
        try {
            return Optional.of(jdbcTemplate.queryForObject(
                    "SELECT * FROM unblind_event WHERE trial_code = ? AND request_id = ?",
                    EVENT_MAPPER, trialCode, requestId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public void insertEvent(UnblindEventRow row) {
        jdbcTemplate.update(
                "INSERT INTO unblind_event(trial_code, screening_no, request_id, status, "
                        + "first_confirmer, second_confirmer, created_at_utc, effective_at_utc) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                row.trialCode(), row.screeningNo(), row.requestId(), row.status(),
                row.firstConfirmer(), row.secondConfirmer(),
                Timestamp.from(row.createdAtUtc()), Timestamp.from(row.effectiveAtUtc()));
    }

    public void insertAudit(String trialCode, String screeningNo, String eventType,
                            Instant eventTime, String requestId, String operator, String detail) {
        jdbcTemplate.update(
                "INSERT INTO unblind_audit(trial_code, screening_no, event_type, event_time_utc, "
                        + "request_id, operator, detail) VALUES (?, ?, ?, ?, ?, ?, ?)",
                trialCode, screeningNo, eventType, Timestamp.from(eventTime),
                requestId, operator, detail);
    }

    public List<AuditView> listAudit(String trialCode, String screeningNo) {
        return jdbcTemplate.query(
                "SELECT id, event_type, event_time_utc, request_id, operator, detail "
                        + "FROM unblind_audit WHERE trial_code = ? AND screening_no = ? ORDER BY id",
                (rs, n) -> new AuditView(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getTimestamp("event_time_utc").toInstant(),
                        rs.getString("request_id"),
                        rs.getString("operator"),
                        rs.getString("detail")),
                trialCode, screeningNo);
    }

    /**
     * 审计事件视图。
     */
    public record AuditView(long id, String eventType, Instant eventTimeUtc,
                            String requestId, String operator, String detail) {
    }
}
