package com.example.starter.radio.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import com.example.starter.radio.domain.ChangeStatus;
import com.example.starter.radio.model.DispensingChange;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 给药时刻变更单及其复核链数据访问。
 * 变更单仅追加与状态流转，不覆盖有效分装；版本替换由分装仓储在同一事务内完成。
 */
@Repository
public class ChangeRepository {

    private final JdbcTemplate jdbc;

    public ChangeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String requestId, String fingerprint, long dispensingId, long expectedVersion,
                       long newAdministrationTimeEpoch, long candidateVersionNo, Double candidateActivityBq,
                       Double candidateDeviationRatio, long nowEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_change (request_id, fingerprint, dispensing_id, expected_version,"
                            + " new_administration_time_epoch, candidate_version_no, candidate_activity_bq,"
                            + " candidate_deviation_ratio, status, created_at_epoch, updated_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, dispensingId);
            ps.setLong(4, expectedVersion);
            ps.setLong(5, newAdministrationTimeEpoch);
            ps.setLong(6, candidateVersionNo);
            if (candidateActivityBq == null) {
                ps.setNull(7, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(7, candidateActivityBq);
            }
            if (candidateDeviationRatio == null) {
                ps.setNull(8, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(8, candidateDeviationRatio);
            }
            ps.setLong(9, nowEpoch);
            ps.setLong(10, nowEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<DispensingChange> findById(long id) {
        return jdbc.query("SELECT * FROM dispensing_change WHERE id = ?", this::map, id).stream().findFirst();
    }

    /** 复核/生效事务内对变更单加行锁。 */
    public Optional<DispensingChange> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM dispensing_change WHERE id = ? FOR UPDATE", this::map, id)
                .stream().findFirst();
    }

    public Optional<DispensingChange> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM dispensing_change WHERE request_id = ?", this::map, requestId)
                .stream().findFirst();
    }

    public List<DispensingChange> findByDispensing(long dispensingId) {
        return jdbc.query("SELECT * FROM dispensing_change WHERE dispensing_id = ? ORDER BY id",
                this::map, dispensingId);
    }

    /** 同一分装是否已存在待复核候选（调用方须持有分装行锁以保证判定与写入原子）。 */
    public boolean existsPendingByDispensing(long dispensingId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM dispensing_change WHERE dispensing_id = ? AND status = 'PENDING'",
                Integer.class, dispensingId);
        return count != null && count > 0;
    }

    public int updateChecker(long id, String checkerColumn, String checker, long nowEpoch) {
        return jdbc.update("UPDATE dispensing_change SET " + checkerColumn + " = ?, updated_at_epoch = ? WHERE id = ?",
                checker, nowEpoch, id);
    }

    public int markApproved(long id, long approvedAtEpoch) {
        return jdbc.update(
                "UPDATE dispensing_change SET status = 'APPROVED', approved_at_epoch = ?, updated_at_epoch = ?"
                        + " WHERE id = ?",
                approvedAtEpoch, approvedAtEpoch, id);
    }

    public int markVoid(long id, long voidedAtEpoch) {
        return jdbc.update(
                "UPDATE dispensing_change SET status = 'VOID', voided_at_epoch = ?, updated_at_epoch = ? WHERE id = ?",
                voidedAtEpoch, voidedAtEpoch, id);
    }

    public int markRejected(long id, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing_change SET status = 'REJECTED', updated_at_epoch = ? WHERE id = ?",
                nowEpoch, id);
    }

    public long insertChangeCheck(long changeId, String checker, int checkSeq, long checkedAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_change_check (change_id, checker, check_seq, checked_at_epoch)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, changeId);
            ps.setString(2, checker);
            ps.setInt(3, checkSeq);
            ps.setLong(4, checkedAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<DispensingCheckView> findChecks(long changeId) {
        return jdbc.query("SELECT * FROM dispensing_change_check WHERE change_id = ? ORDER BY check_seq",
                (rs, i) -> new DispensingCheckView(
                        rs.getLong("id"),
                        rs.getLong("change_id"),
                        rs.getString("checker"),
                        rs.getInt("check_seq"),
                        rs.getLong("checked_at_epoch")),
                changeId);
    }

    private DispensingChange map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double activity = rs.getDouble("candidate_activity_bq");
        Double candidateActivity = rs.wasNull() ? null : activity;
        double deviation = rs.getDouble("candidate_deviation_ratio");
        Double candidateDeviation = rs.wasNull() ? null : deviation;
        long approved = rs.getLong("approved_at_epoch");
        Long approvedAt = rs.wasNull() ? null : approved;
        long voided = rs.getLong("voided_at_epoch");
        Long voidedAt = rs.wasNull() ? null : voided;
        return new DispensingChange(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("dispensing_id"),
                rs.getLong("expected_version"),
                rs.getLong("new_administration_time_epoch"),
                rs.getLong("candidate_version_no"),
                candidateActivity,
                candidateDeviation,
                ChangeStatus.valueOf(rs.getString("status")),
                rs.getString("first_checker"),
                rs.getString("second_checker"),
                approvedAt,
                voidedAt,
                rs.getLong("created_at_epoch"),
                rs.getLong("updated_at_epoch"));
    }

    /** 变更单复核链视图。 */
    public record DispensingCheckView(long id, long changeId, String checker, int checkSeq, long checkedAtEpoch) {
    }
}
