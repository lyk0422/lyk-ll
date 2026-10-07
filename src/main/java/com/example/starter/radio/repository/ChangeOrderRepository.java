package com.example.starter.radio.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import com.example.starter.radio.domain.ChangeOrderStatus;
import com.example.starter.radio.model.ChangeCheck;
import com.example.starter.radio.model.ChangeOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 给药时刻变更候选单及其双人复核链数据访问。
 * 生效、作废与发药竞争均通过先锁分装行再锁候选行（FOR UPDATE）串行化。
 */
@Repository
public class ChangeOrderRepository {

    private final JdbcTemplate jdbc;

    public ChangeOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String requestId, String fingerprint, long dispensingId, int expectedVersion,
                       int prescriptionVersion, Long newPrescriptionId, long newAdministrationTimeEpoch,
                       int candidateVersionNo, Double candidateActivityBq, Double candidateDeviationRatio,
                       String calcStatus, ChangeOrderStatus status, long nowEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_change_order (request_id, fingerprint, dispensing_id, expected_version,"
                            + " prescription_version, new_prescription_id, new_administration_time_epoch,"
                            + " candidate_version_no, candidate_activity_bq, candidate_deviation_ratio,"
                            + " calc_status, status, created_at_epoch, updated_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, dispensingId);
            ps.setInt(4, expectedVersion);
            ps.setInt(5, prescriptionVersion);
            if (newPrescriptionId == null) {
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setLong(6, newPrescriptionId);
            }
            ps.setLong(7, newAdministrationTimeEpoch);
            ps.setInt(8, candidateVersionNo);
            if (candidateActivityBq == null) {
                ps.setNull(9, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(9, candidateActivityBq);
            }
            if (candidateDeviationRatio == null) {
                ps.setNull(10, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(10, candidateDeviationRatio);
            }
            ps.setString(11, calcStatus);
            ps.setString(12, status.name());
            ps.setLong(13, nowEpoch);
            ps.setLong(14, nowEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<ChangeOrder> findById(long id) {
        return jdbc.query("SELECT * FROM dispensing_change_order WHERE id = ?",
                this::map, id).stream().findFirst();
    }

    /**
     * 候选行锁；调用方须已持有对应分装行锁，统一锁顺序避免死锁。
     */
    public Optional<ChangeOrder> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM dispensing_change_order WHERE id = ? FOR UPDATE",
                this::map, id).stream().findFirst();
    }

    public Optional<ChangeOrder> findBySubmitRequestId(String requestId) {
        return jdbc.query("SELECT * FROM dispensing_change_order WHERE request_id = ?",
                this::map, requestId).stream().findFirst();
    }

    public Optional<ChangeOrder> findByVoidRequestId(String voidRequestId) {
        return jdbc.query("SELECT * FROM dispensing_change_order WHERE void_request_id = ?",
                this::map, voidRequestId).stream().findFirst();
    }

    public Optional<ChangeOrder> findActiveByDispensingForUpdate(long dispensingId) {
        return jdbc.query(
                "SELECT * FROM dispensing_change_order WHERE dispensing_id = ? AND status = 'PENDING' FOR UPDATE",
                this::map, dispensingId).stream().findFirst();
    }

    public List<ChangeOrder> findByDispensing(long dispensingId) {
        return jdbc.query(
                "SELECT * FROM dispensing_change_order WHERE dispensing_id = ? ORDER BY id",
                this::map, dispensingId);
    }

    /**
     * 证据查询：某撤回单终结的全部时刻候选（状态 WITHDRAWN）。
     */
    public List<ChangeOrder> findByTerminatedWithdrawal(long withdrawalId) {
        return jdbc.query(
                "SELECT * FROM dispensing_change_order WHERE terminated_by_withdrawal_id = ? ORDER BY id",
                this::map, withdrawalId);
    }

    public int updateReviewer(long id, String reviewerColumn, String reviewer, long nowEpoch) {
        return jdbc.update("UPDATE dispensing_change_order SET " + reviewerColumn + " = ?, updated_at_epoch = ?"
                + " WHERE id = ?", reviewer, nowEpoch, id);
    }

    /**
     * 双人复核通过、生效：记录第二复核人、新生效处方版本与生效时刻，候选置为 APPROVED。
     */
    public int markApproved(long id, String secondReviewer, long newPrescriptionId, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing_change_order SET second_reviewer = ?, new_prescription_id = ?,"
                        + " status = 'APPROVED', approved_at_epoch = ?, updated_at_epoch = ? WHERE id = ?",
                secondReviewer, newPrescriptionId, nowEpoch, nowEpoch, id);
    }

    public int markVoid(long id, String voidRequestId, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing_change_order SET status = 'VOID', void_request_id = ?,"
                        + " voided_at_epoch = ?, updated_at_epoch = ? WHERE id = ?",
                voidRequestId, nowEpoch, nowEpoch, id);
    }

    public int markSuperseded(long id, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing_change_order SET status = 'SUPERSEDED', updated_at_epoch = ? WHERE id = ?",
                nowEpoch, id);
    }

    /**
     * 整批撤回批准最终事务：终结该分装全部未生效（PENDING）时刻候选，状态置 WITHDRAWN 并记录撤回单证据。
     */
    public int markWithdrawn(long id, long withdrawalId, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing_change_order SET status = 'WITHDRAWN',"
                        + " terminated_by_withdrawal_id = ?, updated_at_epoch = ? WHERE id = ?",
                withdrawalId, nowEpoch, id);
    }

    public long insertCheck(long changeOrderId, String reviewer, int reviewSeq, long reviewedAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_change_check (change_order_id, reviewer, review_seq, reviewed_at_epoch)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, changeOrderId);
            ps.setString(2, reviewer);
            ps.setInt(3, reviewSeq);
            ps.setLong(4, reviewedAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<ChangeCheck> findChecks(long changeOrderId) {
        return jdbc.query(
                "SELECT * FROM dispensing_change_check WHERE change_order_id = ? ORDER BY review_seq",
                (rs, i) -> new ChangeCheck(
                        rs.getLong("id"),
                        rs.getLong("change_order_id"),
                        rs.getString("reviewer"),
                        rs.getInt("review_seq"),
                        rs.getLong("reviewed_at_epoch")),
                changeOrderId);
    }

    private ChangeOrder map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double activity = rs.getDouble("candidate_activity_bq");
        Double candidateActivityBq = rs.wasNull() ? null : activity;
        double deviation = rs.getDouble("candidate_deviation_ratio");
        Double candidateDeviationRatio = rs.wasNull() ? null : deviation;
        return new ChangeOrder(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("dispensing_id"),
                rs.getInt("expected_version"),
                rs.getInt("prescription_version"),
                (Long) rs.getObject("new_prescription_id"),
                rs.getLong("new_administration_time_epoch"),
                rs.getInt("candidate_version_no"),
                candidateActivityBq,
                candidateDeviationRatio,
                rs.getString("calc_status"),
                ChangeOrderStatus.valueOf(rs.getString("status")),
                rs.getString("first_reviewer"),
                rs.getString("second_reviewer"),
                (Long) rs.getObject("approved_at_epoch"),
                (Long) rs.getObject("voided_at_epoch"),
                rs.getString("void_request_id"),
                (Long) rs.getObject("terminated_by_withdrawal_id"),
                rs.getLong("created_at_epoch"),
                rs.getLong("updated_at_epoch"));
    }
}
