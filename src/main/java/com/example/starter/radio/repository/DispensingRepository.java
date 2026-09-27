package com.example.starter.radio.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import com.example.starter.radio.domain.DispensingStatus;
import com.example.starter.radio.model.Dispensing;
import com.example.starter.radio.model.DispensingCheck;
import com.example.starter.radio.model.LateEvent;
import com.example.starter.radio.model.ReconciliationLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 分装批次、双人核对链、勾稽台账与迟到事件数据访问。
 */
@Repository
public class DispensingRepository {

    private final JdbcTemplate jdbc;

    public DispensingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertDispensing(String requestId, String fingerprint, long motherLiquorId,
                                 long prescriptionId, long calibratorId, int version, long administrationTimeEpoch,
                                 Double dispensedActivityBq,
                                 Double deviationRatio, DispensingStatus status, long nowEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing (request_id, fingerprint, mother_liquor_id, prescription_id,"
                            + " calibrator_id, version, administration_time_epoch, dispensed_activity_bq,"
                            + " deviation_ratio, status, created_at_epoch, updated_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, motherLiquorId);
            ps.setLong(4, prescriptionId);
            ps.setLong(5, calibratorId);
            ps.setInt(6, version);
            ps.setLong(7, administrationTimeEpoch);
            if (dispensedActivityBq == null) {
                ps.setNull(8, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(8, dispensedActivityBq);
            }
            if (deviationRatio == null) {
                ps.setNull(9, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(9, deviationRatio);
            }
            ps.setString(10, status.name());
            ps.setLong(11, nowEpoch);
            ps.setLong(12, nowEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<Dispensing> findDispensingById(long id) {
        return jdbc.query("SELECT * FROM dispensing WHERE id = ?",
                this::mapDispensing, id).stream().findFirst();
    }

    /**
     * 发药事务内行锁，串行化并发发药与核对。
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
     * 同一母液批次下未拒绝的全部分装，用于勾稽分装总量复算。
     */
    public List<Dispensing> findActiveByMotherLiquor(long motherLiquorId) {
        return jdbc.query("SELECT * FROM dispensing WHERE mother_liquor_id = ? AND status <> 'REJECTED'",
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
                        + " dispense_fingerprint = ?, dispensed_at_epoch = ?, updated_at_epoch = ? WHERE id = ?",
                dispenseRequestId, dispenseFingerprint, nowEpoch, nowEpoch, id);
    }

    /**
     * 变更生效同事务替换有效版本：给药时刻、目标活度、偏差与版本号一并更新，
     * 状态回到 CALCULATED 并清空旧版本双人核对有效性（旧签认行本身保留）。
     */
    public int applyNewVersion(long id, int newVersion, long administrationTimeEpoch,
                               double dispensedActivityBq, double deviationRatio, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing SET version = ?, administration_time_epoch = ?, dispensed_activity_bq = ?,"
                        + " deviation_ratio = ?, status = 'CALCULATED', first_checker = NULL,"
                        + " second_checker = NULL, updated_at_epoch = ? WHERE id = ?",
                newVersion, administrationTimeEpoch, dispensedActivityBq, deviationRatio, nowEpoch, id);
    }

    public long insertCheck(long dispensingId, int version, String checker, int checkSeq, long checkedAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_check (dispensing_id, version, checker, check_seq, checked_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, dispensingId);
            ps.setInt(2, version);
            ps.setString(3, checker);
            ps.setInt(4, checkSeq);
            ps.setLong(5, checkedAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<DispensingCheck> findChecks(long dispensingId) {
        return jdbc.query("SELECT * FROM dispensing_check WHERE dispensing_id = ? ORDER BY version, check_seq",
                (rs, i) -> new DispensingCheck(
                        rs.getLong("id"),
                        rs.getLong("dispensing_id"),
                        rs.getInt("version"),
                        rs.getString("checker"),
                        rs.getInt("check_seq"),
                        rs.getLong("checked_at_epoch")),
                dispensingId);
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

    private Dispensing mapDispensing(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        double dispensed = rs.getDouble("dispensed_activity_bq");
        Double dispensedActivity = rs.wasNull() ? null : dispensed;
        double deviation = rs.getDouble("deviation_ratio");
        Double deviationRatio = rs.wasNull() ? null : deviation;
        long dispensedAt = rs.getLong("dispensed_at_epoch");
        Long dispensedAtEpoch = rs.wasNull() ? null : dispensedAt;
        return new Dispensing(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("mother_liquor_id"),
                rs.getLong("prescription_id"),
                rs.getLong("calibrator_id"),
                rs.getInt("version"),
                rs.getLong("administration_time_epoch"),
                dispensedActivity,
                deviationRatio,
                DispensingStatus.valueOf(rs.getString("status")),
                rs.getString("first_checker"),
                rs.getString("second_checker"),
                rs.getString("dispense_request_id"),
                rs.getString("dispense_fingerprint"),
                dispensedAtEpoch);
    }
}
