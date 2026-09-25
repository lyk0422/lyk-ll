package com.example.starter.trial.domain;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 受试者入组记录仓储：盲态底表读写。
 */
@Repository
public class EnrollmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public EnrollmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 入组记录行（含真实组别，仅限服务层内部使用，不得直接返回给未揭盲调用方）。
     */
    public record EnrollmentRow(
            long id,
            String trialCode,
            String centerCode,
            String screeningNo,
            String requestId,
            Instant consentTimeUtc,
            long randomNo,
            long blockNo,
            int positionInBlock,
            String groupCode,
            String blindedCode,
            String status,
            String fingerprint,
            Instant enrolledAtUtc,
            Instant unblindedAtUtc) {
    }

    private static final RowMapper<EnrollmentRow> ROW_MAPPER = (rs, rowNum) -> new EnrollmentRow(
            rs.getLong("id"),
            rs.getString("trial_code"),
            rs.getString("center_code"),
            rs.getString("screening_no"),
            rs.getString("request_id"),
            rs.getTimestamp("consent_time_utc").toInstant(),
            rs.getLong("random_no"),
            rs.getLong("block_no"),
            rs.getInt("position_in_block"),
            rs.getString("group_code"),
            rs.getString("blinded_code"),
            rs.getString("status"),
            rs.getString("fingerprint"),
            rs.getTimestamp("enrolled_at_utc").toInstant(),
            toInstant(rs.getTimestamp("unblinded_at_utc")));

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private static final String COLUMNS =
            "id, trial_code, center_code, screening_no, request_id, consent_time_utc, random_no, "
                    + "block_no, position_in_block, group_code, blinded_code, status, fingerprint, "
                    + "enrolled_at_utc, unblinded_at_utc";

    public Optional<EnrollmentRow> findByRequestId(String trialCode, String requestId) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM subject_enrollment WHERE trial_code = ? AND request_id = ?",
                ROW_MAPPER, trialCode, requestId).stream().findFirst();
    }

    public Optional<EnrollmentRow> findByScreeningNo(String trialCode, String screeningNo) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM subject_enrollment WHERE trial_code = ? AND screening_no = ?",
                ROW_MAPPER, trialCode, screeningNo).stream().findFirst();
    }

    /**
     * 事务内锁定受试者行，串行化针对同一受试者的并发揭盲。
     */
    public Optional<EnrollmentRow> lockByScreeningNo(String trialCode, String screeningNo) {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM subject_enrollment WHERE trial_code = ? AND screening_no = ? FOR UPDATE",
                ROW_MAPPER, trialCode, screeningNo).stream().findFirst();
    }

    /**
     * 落库入组记录。
     */
    public void insert(EnrollmentRow row) {
        jdbcTemplate.update(
                "INSERT INTO subject_enrollment(trial_code, center_code, screening_no, request_id, "
                        + "consent_time_utc, random_no, block_no, position_in_block, group_code, blinded_code, "
                        + "status, fingerprint, enrolled_at_utc) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                row.trialCode(), row.centerCode(), row.screeningNo(), row.requestId(),
                Timestamp.from(row.consentTimeUtc()), row.randomNo(), row.blockNo(), row.positionInBlock(),
                row.groupCode(), row.blindedCode(), row.status(), row.fingerprint(),
                Timestamp.from(row.enrolledAtUtc()));
    }

    /** 揭盲生效：受试者置终态 UNBLINDED。 */
    public int markUnblinded(String trialCode, String screeningNo, Instant unblindedAt) {
        return jdbcTemplate.update(
                "UPDATE subject_enrollment SET status = ?, unblinded_at_utc = ? "
                        + "WHERE trial_code = ? AND screening_no = ? AND status = ?",
                TrialConstants.STATUS_UNBLINDED, Timestamp.from(unblindedAt),
                trialCode, screeningNo, TrialConstants.STATUS_ENROLLED);
    }
}
