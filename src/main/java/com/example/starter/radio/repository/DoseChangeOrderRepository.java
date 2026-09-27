package com.example.starter.radio.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import com.example.starter.radio.domain.DoseChangeStatus;
import com.example.starter.radio.model.DoseChangeCheck;
import com.example.starter.radio.model.DoseChangeOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 给药时刻变更候选单与双人复核链数据访问。
 * 以 pending_dispensing_id 唯一索引保证同一分装至多一个待决候选；终局时置 NULL。
 */
@Repository
public class DoseChangeOrderRepository {

    private final JdbcTemplate jdbc;

    public DoseChangeOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(DoseChangeOrder o) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dose_change_order (request_id, fingerprint, dispensing_id, expected_version,"
                            + " prescription_id, new_administration_time_epoch, candidate_version,"
                            + " candidate_activity_bq, candidate_deviation_ratio, mother_initial_activity_bq,"
                            + " mother_reference_time_epoch, half_life_seconds, inventory_available_bq, status,"
                            + " pending_dispensing_id, submitted_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, o.requestId());
            ps.setString(2, o.fingerprint());
            ps.setLong(3, o.dispensingId());
            ps.setInt(4, o.expectedVersion());
            ps.setLong(5, o.prescriptionId());
            ps.setLong(6, o.newAdministrationTimeEpoch());
            ps.setInt(7, o.candidateVersion());
            ps.setDouble(8, o.candidateActivityBq());
            ps.setDouble(9, o.candidateDeviationRatio());
            ps.setDouble(10, o.motherInitialActivityBq());
            ps.setLong(11, o.motherReferenceTimeEpoch());
            ps.setLong(12, o.halfLifeSeconds());
            ps.setDouble(13, o.inventoryAvailableBq());
            ps.setLong(14, o.dispensingId());
            ps.setLong(15, o.submittedAtEpoch());
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<DoseChangeOrder> findById(long id) {
        return jdbc.query("SELECT * FROM dose_change_order WHERE id = ?", this::map, id).stream().findFirst();
    }

    /**
     * 复核/作废/发药竞争事务内行锁。
     */
    public Optional<DoseChangeOrder> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM dose_change_order WHERE id = ? FOR UPDATE", this::map, id)
                .stream().findFirst();
    }

    public Optional<DoseChangeOrder> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM dose_change_order WHERE request_id = ?", this::map, requestId)
                .stream().findFirst();
    }

    /**
     * 某分装当前待决候选（FOR UPDATE 与发药竞争串行化）。
     */
    public Optional<DoseChangeOrder> findPendingByDispensingForUpdate(long dispensingId) {
        return jdbc.query(
                "SELECT * FROM dose_change_order WHERE dispensing_id = ? AND status = 'PENDING' FOR UPDATE",
                this::map, dispensingId).stream().findFirst();
    }

    public List<DoseChangeOrder> findByDispensing(long dispensingId) {
        return jdbc.query("SELECT * FROM dose_change_order WHERE dispensing_id = ? ORDER BY id",
                this::map, dispensingId);
    }

    public int updateReviewer(long id, String reviewerColumn, String reviewer) {
        return jdbc.update("UPDATE dose_change_order SET " + reviewerColumn + " = ? WHERE id = ?",
                reviewer, id);
    }

    /**
     * 终局：生效/拒绝/作废；清空 pending_dispensing_id 释放唯一待决槽。
     */
    public int decide(long id, DoseChangeStatus status, long decidedAtEpoch) {
        return jdbc.update(
                "UPDATE dose_change_order SET status = ?, pending_dispensing_id = NULL, decided_at_epoch = ?"
                        + " WHERE id = ?",
                status.name(), decidedAtEpoch, id);
    }

    public long insertCheck(long changeOrderId, String checker, int checkSeq, long checkedAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dose_change_check (change_order_id, checker, check_seq, checked_at_epoch)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, changeOrderId);
            ps.setString(2, checker);
            ps.setInt(3, checkSeq);
            ps.setLong(4, checkedAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<DoseChangeCheck> findChecks(long changeOrderId) {
        return jdbc.query("SELECT * FROM dose_change_check WHERE change_order_id = ? ORDER BY check_seq",
                (rs, i) -> new DoseChangeCheck(
                        rs.getLong("id"),
                        rs.getLong("change_order_id"),
                        rs.getString("checker"),
                        rs.getInt("check_seq"),
                        rs.getLong("checked_at_epoch")),
                changeOrderId);
    }

    private DoseChangeOrder map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DoseChangeOrder(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("dispensing_id"),
                rs.getInt("expected_version"),
                rs.getLong("prescription_id"),
                rs.getLong("new_administration_time_epoch"),
                rs.getInt("candidate_version"),
                rs.getDouble("candidate_activity_bq"),
                rs.getDouble("candidate_deviation_ratio"),
                rs.getDouble("mother_initial_activity_bq"),
                rs.getLong("mother_reference_time_epoch"),
                rs.getLong("half_life_seconds"),
                rs.getDouble("inventory_available_bq"),
                DoseChangeStatus.valueOf(rs.getString("status")),
                rs.getString("first_reviewer"),
                rs.getString("second_reviewer"),
                rs.getLong("submitted_at_epoch"),
                (Long) rs.getObject("decided_at_epoch"));
    }
}
