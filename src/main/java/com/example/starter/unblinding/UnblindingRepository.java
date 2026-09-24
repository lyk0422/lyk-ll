package com.example.starter.unblinding;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 揭盲相关数据访问：授权人名单、揭盲记录、访视数据排除标记。
 */
@Repository
public class UnblindingRepository {

    private final JdbcTemplate jdbc;

    public UnblindingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isAuthorized(long trialId, String personId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM authorized_unblinder WHERE trial_id = ? AND person_id = ?",
                Integer.class, trialId, personId);
        return count != null && count > 0;
    }

    public UnblindingRecord findByScreening(long trialId, String screeningNo) {
        try {
            return jdbc.queryForObject(
                    "SELECT * FROM unblinding_record WHERE trial_id = ? AND screening_no = ?",
                    this::mapRecord, trialId, screeningNo);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public void insert(UnblindingRecord record) {
        jdbc.update(
                "INSERT INTO unblinding_record (trial_id, screening_no, approver1, approver2,"
                        + " reason, revealed_arm, effective_at) VALUES (?,?,?,?,?,?,?)",
                record.trialId(), record.screeningNo(), record.approver1(), record.approver2(),
                record.reason(), String.valueOf(record.revealedArm()),
                Timestamp.from(record.effectiveAt()));
    }

    /**
     * 将受试者揭盲生效时刻及之后的访视数据标记为排除出主要分析集。
     */
    public int excludeSubsequentVisits(long trialId, String screeningNo, Instant effectiveAt) {
        return jdbc.update(
                "UPDATE visit_record SET excluded_from_pas = TRUE"
                        + " WHERE trial_id = ? AND screening_no = ? AND visit_time >= ?",
                trialId, screeningNo, Timestamp.from(effectiveAt));
    }

    private UnblindingRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        return new UnblindingRecord(
                rs.getLong("id"),
                rs.getLong("trial_id"),
                rs.getString("screening_no"),
                rs.getString("approver1"),
                rs.getString("approver2"),
                rs.getString("reason"),
                rs.getString("revealed_arm").charAt(0),
                rs.getTimestamp("effective_at").toInstant());
    }
}
