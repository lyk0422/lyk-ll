package com.example.starter.radio.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.DispensingCheck;
import com.example.starter.radio.model.DispensingVersion;
import com.example.starter.radio.model.LateEvent;
import com.example.starter.radio.model.ReconciliationLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 分装批次、计算版本、双人核对链、勾稽台账与迟到事件数据访问。
 * 行锁（FOR UPDATE）串行化并发发药、核对与变更生效；锁顺序统一为先分装行后变更单，避免死锁。
 */
@Repository
public class DispensingRepository {

    private final JdbcTemplate jdbc;

    public DispensingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertDispensing(String requestId, String fingerprint, long motherLiquorId,
                                 long prescriptionId, long calibratorId, Double dispensedActivityBq,
                                 Double deviationRatio, DispensingStatus status, long nowEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing (request_id, fingerprint, mother_liquor_id, prescription_id,"
                            + " calibrator_id, dispensed_activity_bq, deviation_ratio, status,"
                            + " version_no, created_at_epoch, updated_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, motherLiquorId);
            ps.setLong(4, prescriptionId);
            ps.setLong(5, calibratorId);
            if (dispensedActivityBq == null) {
                ps.setNull(6, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(6, dispensedActivityBq);
            }
            if (deviationRatio == null) {
                ps.setNull(7, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(7, deviationRatio);
            }
            ps.setString(8, status.name());
            ps.setLong(9, nowEpoch);
            ps.setLong(10, nowEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Dispensing> findDispensingById(long id) {
        return jdbc.query("SELECT * FROM dispensing WHERE id = ?",
                this::mapDispensing, id).stream().findFirst();
    }

    /**
     * 发药/变更事务内行锁，串行化并发发药、核对与变更生效。
     */
    public Optional<Dispensing> findDispensingByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM dispensing WHERE id = ? FOR UPDATE",
                this::mapDispensing, id).stream().findFirst();
    }

    public Optional<Dispensing> findDispensingByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM dispensing WHERE request_id = ?",
                this::mapDispensing, requestId).stream().findFirst();
    }

    /**
     * 撤回单事务内按主键升序对一批分装加行锁（FOR UPDATE），串行化撤回与发药/改版竞争。
     * 调用方须保证 ids 已升序，统一锁顺序避免死锁。
     */
    public List<Dispensing> findByIdsForUpdateOrdered(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        Object[] args = ids.toArray();
        return jdbc.query("SELECT * FROM dispensing WHERE id IN (" + placeholders + ") ORDER BY id FOR UPDATE",
                this::mapDispensing, args);
    }

    /**
     * 登记一批分装被同一张待批准撤回单占用（软引用，不加外键）；同事务与撤回单创建一起提交。
     */
    public int markActiveWithdrawal(List<Long> ids, long withdrawalId, long nowEpoch) {
        if (ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        Object[] args = new Object[ids.size() + 2];
        args[0] = withdrawalId;
        args[1] = nowEpoch;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 2] = ids.get(i);
        }
        return jdbc.update("UPDATE dispensing SET active_withdrawal_id = ?, updated_at_epoch = ? WHERE id IN ("
                + placeholders + ")", args);
    }

    /**
     * 未批准撤回单整体撤销：释放其全部分装占用。
     */
    public int clearActiveWithdrawal(List<Long> ids, long nowEpoch) {
        if (ids.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", ids.stream().map(x -> "?").toList());
        Object[] args = new Object[ids.size() + 1];
        args[0] = nowEpoch;
        for (int i = 0; i < ids.size(); i++) {
            args[i + 1] = ids.get(i);
        }
        return jdbc.update(
                "UPDATE dispensing SET active_withdrawal_id = NULL, updated_at_epoch = ? WHERE id IN ("
                        + placeholders + ")", args);
    }

    /**
     * 整批撤回批准最终事务：分装转 VOID 终态，释放撤回占用并清空挂起候选引用；
     * 双人核对字段置空使核对不再有效；计算版本号、原计算版本行与签认行保留为证据。
     */
    public int markVoidForWithdrawal(long id, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing SET status = 'VOID', first_checker = NULL, second_checker = NULL,"
                        + " active_change_order_id = NULL, active_withdrawal_id = NULL, updated_at_epoch = ?"
                        + " WHERE id = ?",
                nowEpoch, id);
    }

    /**
     * 同一母液批次下未拒绝、未撤回作废的全部分装，用于勾稽分装总量复算。
     * REJECTED 从未占用库存；VOID 为未发药撤回（基线计算本就不扣母液），二者均不得计入分装总量。
     */
    public List<Dispensing> findActiveByMotherLiquor(long motherLiquorId) {
        return jdbc.query(
                "SELECT * FROM dispensing WHERE mother_liquor_id = ? AND status NOT IN ('REJECTED', 'VOID')",
                this::mapDispensing, motherLiquorId);
    }

    public int updateChecker(long id, String checkerColumn, String checker, long nowEpoch) {
        return jdbc.update("UPDATE dispensing SET " + checkerColumn + " = ?, updated_at_epoch = ? WHERE id = ?",
                checker, nowEpoch, id);
    }

    public int updateStatus(long id, DispensingStatus status, long nowEpoch) {
        return jdbc.update("UPDATE dispensing SET status = ?, updated_at_epoch = ? WHERE id = ?",
                status.name(), nowEpoch, id);
    }

    public int markDispensed(long id, String dispenseRequestId, String dispenseFingerprint, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing SET status = 'DISPENSED', dispense_request_id = ?,"
                        + " dispense_fingerprint = ?, dispensed_at_epoch = ?, active_change_order_id = NULL,"
                        + " updated_at_epoch = ? WHERE id = ?",
                dispenseRequestId, dispenseFingerprint, nowEpoch, nowEpoch, id);
    }

    /**
     * 登记或清除挂起候选（changeOrderId 传 null 即清除）；同一分装同时只允许一个候选。
     */
    public int markActiveChangeOrder(long dispensingId, Long changeOrderId, long nowEpoch) {
        return jdbc.update("UPDATE dispensing SET active_change_order_id = ?, updated_at_epoch = ? WHERE id = ?",
                changeOrderId, nowEpoch, dispensingId);
    }

    /**
     * 变更生效：同事务替换处方版本、给药时刻对应活度与偏差、计算版本，清除旧双人核对有效性，
     * 状态回落为待核对，等待按新版本重新双人核对；旧计算版本与旧签认行不在此删除。
     */
    public int applyNewVersion(long dispensingId, long newPrescriptionId, Double newActivityBq,
                               Double newDeviationRatio, int newVersionNo, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing SET prescription_id = ?, dispensed_activity_bq = ?, deviation_ratio = ?,"
                        + " status = 'CALCULATED', first_checker = NULL, second_checker = NULL,"
                        + " version_no = ?, active_change_order_id = NULL, updated_at_epoch = ? WHERE id = ?",
                newPrescriptionId,
                newActivityBq == null ? null : newActivityBq,
                newDeviationRatio == null ? null : newDeviationRatio,
                newVersionNo, nowEpoch, dispensingId);
    }

    public long insertCheck(long dispensingId, int versionNo, String checker, int checkSeq, long checkedAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_check (dispensing_id, version_no, checker, check_seq, checked_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, dispensingId);
            ps.setInt(2, versionNo);
            ps.setString(3, checker);
            ps.setInt(4, checkSeq);
            ps.setLong(5, checkedAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<DispensingCheck> findChecks(long dispensingId) {
        return jdbc.query(
                "SELECT * FROM dispensing_check WHERE dispensing_id = ? ORDER BY version_no, check_seq",
                (rs, i) -> new DispensingCheck(
                        rs.getLong("id"),
                        rs.getLong("dispensing_id"),
                        rs.getInt("version_no"),
                        rs.getString("checker"),
                        rs.getInt("check_seq"),
                        rs.getLong("checked_at_epoch")),
                dispensingId);
    }

    /**
     * 插入不可变计算版本证据。
     */
    public long insertVersion(long dispensingId, int versionNo, long prescriptionId, long administrationTimeEpoch,
                              Double dispensedActivityBq, Double deviationRatio, String calcStatus,
                              Long changeOrderId, long createdAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_version (dispensing_id, version_no, prescription_id,"
                            + " administration_time_epoch, dispensed_activity_bq, deviation_ratio, calc_status,"
                            + " change_order_id, created_at_epoch) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, dispensingId);
            ps.setInt(2, versionNo);
            ps.setLong(3, prescriptionId);
            ps.setLong(4, administrationTimeEpoch);
            if (dispensedActivityBq == null) {
                ps.setNull(5, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(5, dispensedActivityBq);
            }
            if (deviationRatio == null) {
                ps.setNull(6, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(6, deviationRatio);
            }
            ps.setString(7, calcStatus);
            if (changeOrderId == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, changeOrderId);
            }
            ps.setLong(9, createdAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<DispensingVersion> findVersions(long dispensingId) {
        return jdbc.query(
                "SELECT * FROM dispensing_version WHERE dispensing_id = ? ORDER BY version_no",
                this::mapVersion, dispensingId);
    }

    public long insertLedger(ReconciliationLedger ledger, long createdAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO reconciliation_ledger (dispensing_id, initial_activity_bq, dispensed_total_bq,"
                            + " remaining_activity_bq, decay_residual_bq, balanced, measured_at_epoch, created_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ledger.dispensingId());
            ps.setDouble(2, ledger.initialActivityBq());
            ps.setDouble(3, ledger.dispensedTotalBq());
            ps.setDouble(4, ledger.remainingActivityBq());
            ps.setDouble(5, ledger.decayResidualBq());
            ps.setBoolean(6, ledger.balanced());
            ps.setLong(7, ledger.measuredAtEpoch());
            ps.setLong(8, createdAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<ReconciliationLedger> findLedger(long dispensingId) {
        return jdbc.query("SELECT * FROM reconciliation_ledger WHERE dispensing_id = ?",
                (rs, i) -> new ReconciliationLedger(
                        rs.getLong("id"),
                        rs.getLong("dispensing_id"),
                        rs.getDouble("initial_activity_bq"),
                        rs.getDouble("dispensed_total_bq"),
                        rs.getDouble("remaining_activity_bq"),
                        rs.getDouble("decay_residual_bq"),
                        rs.getBoolean("balanced"),
                        rs.getLong("measured_at_epoch")),
                dispensingId).stream().findFirst();
    }

    public long insertLateEvent(long dispensingId, String eventType, String payload, long receivedAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO late_event (dispensing_id, event_type, payload, received_at_epoch)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, dispensingId);
            ps.setString(2, eventType);
            ps.setString(3, payload);
            ps.setLong(4, receivedAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<LateEvent> findLateEvents(long dispensingId) {
        return jdbc.query("SELECT * FROM late_event WHERE dispensing_id = ? ORDER BY id",
                (rs, i) -> new LateEvent(
                        rs.getLong("id"),
                        rs.getLong("dispensing_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getLong("received_at_epoch")),
                dispensingId);
    }

    private DispensingVersion mapVersion(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double activity = rs.getDouble("dispensed_activity_bq");
        Double activityBq = rs.wasNull() ? null : activity;
        double deviation = rs.getDouble("deviation_ratio");
        Double deviationRatio = rs.wasNull() ? null : deviation;
        Long changeOrderId = (Long) rs.getObject("change_order_id");
        return new DispensingVersion(
                rs.getLong("id"),
                rs.getLong("dispensing_id"),
                rs.getInt("version_no"),
                rs.getLong("prescription_id"),
                rs.getLong("administration_time_epoch"),
                activityBq,
                deviationRatio,
                rs.getString("calc_status"),
                changeOrderId,
                rs.getLong("created_at_epoch"));
    }

    private Dispensing mapDispensing(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double dispensed = rs.getDouble("dispensed_activity_bq");
        Double dispensedActivity = rs.wasNull() ? null : dispensed;
        double deviation = rs.getDouble("deviation_ratio");
        Double deviationRatio = rs.wasNull() ? null : deviation;
        long dispensedAt = rs.getLong("dispensed_at_epoch");
        Long dispensedAtEpoch = rs.wasNull() ? null : dispensedAt;
        Long activeChangeOrderId = (Long) rs.getObject("active_change_order_id");
        Long activeWithdrawalId = (Long) rs.getObject("active_withdrawal_id");
        return new Dispensing(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("mother_liquor_id"),
                rs.getLong("prescription_id"),
                rs.getLong("calibrator_id"),
                dispensedActivity,
                deviationRatio,
                DispensingStatus.valueOf(rs.getString("status")),
                rs.getString("first_checker"),
                rs.getString("second_checker"),
                rs.getString("dispense_request_id"),
                rs.getString("dispense_fingerprint"),
                dispensedAtEpoch,
                rs.getInt("version_no"),
                activeChangeOrderId,
                activeWithdrawalId);
    }
}
