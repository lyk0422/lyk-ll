package com.example.starter.radio.repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

import com.example.starter.radio.domain.WithdrawalStatus;
import com.example.starter.radio.model.WithdrawalApproval;
import com.example.starter.radio.model.WithdrawalItem;
import com.example.starter.radio.model.WithdrawalOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 未发药整批撤回单、冻结清单与双人批准链数据访问。
 * 任务“至多属于一张活动撤回单”由冻结清单唯一索引 (dispensing_id, active_order_flag) 强制：
 * PENDING 期间为 1，批准或撤销在同一事务内置 NULL 释放；并发占用以 DuplicateKeyException 串行化。
 */
@Repository
public class WithdrawalRepository {

    private final JdbcTemplate jdbc;

    public WithdrawalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertOrder(String requestId, String fingerprint, long motherLiquorId, String reason,
                            WithdrawalStatus status, long nowEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_withdrawal_order (request_id, fingerprint, mother_liquor_id, reason,"
                            + " status, version_no, created_at_epoch, updated_at_epoch)"
                            + " VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setLong(3, motherLiquorId);
            ps.setString(4, reason);
            ps.setString(5, status.name());
            ps.setLong(6, nowEpoch);
            ps.setLong(7, nowEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<WithdrawalOrder> findOrderById(long id) {
        return jdbc.query("SELECT * FROM dispensing_withdrawal_order WHERE id = ?",
                this::mapOrder, id).stream().findFirst();
    }

    /** 撤回单行锁；与发药/变更竞争时调用方须先持相关分装行锁，统一锁顺序避免死锁。 */
    public Optional<WithdrawalOrder> findOrderByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM dispensing_withdrawal_order WHERE id = ? FOR UPDATE",
                this::mapOrder, id).stream().findFirst();
    }

    public Optional<WithdrawalOrder> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM dispensing_withdrawal_order WHERE request_id = ?",
                this::mapOrder, requestId).stream().findFirst();
    }

    public Optional<WithdrawalOrder> findByCancelRequestId(String cancelRequestId) {
        return jdbc.query("SELECT * FROM dispensing_withdrawal_order WHERE cancel_request_id = ?",
                this::mapOrder, cancelRequestId).stream().findFirst();
    }

    /**
     * 冻结清单项插入。active_order_flag 默认 1 表示活动占用；
     * 若该分装已属其它活动撤回单，唯一索引 (dispensing_id, active_order_flag) 抛 DuplicateKeyException。
     */
    public long insertItem(long withdrawalOrderId, long dispensingId, int frozenVersionNo,
                           String reason, long nowEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_withdrawal_item (withdrawal_order_id, dispensing_id,"
                            + " frozen_version_no, reason, active_order_flag, created_at_epoch)"
                            + " VALUES (?, ?, ?, ?, 1, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, withdrawalOrderId);
            ps.setLong(2, dispensingId);
            ps.setInt(3, frozenVersionNo);
            ps.setString(4, reason);
            ps.setLong(5, nowEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<WithdrawalItem> findItems(long withdrawalOrderId) {
        return jdbc.query(
                "SELECT * FROM dispensing_withdrawal_item WHERE withdrawal_order_id = ? ORDER BY id",
                this::mapItem, withdrawalOrderId);
    }

    /** 该分装当前是否已被某张活动（PENDING）撤回单占用。 */
    public Optional<Long> findActiveOrderIdByDispensing(long dispensingId) {
        return jdbc.query(
                "SELECT withdrawal_order_id FROM dispensing_withdrawal_item"
                        + " WHERE dispensing_id = ? AND active_order_flag = 1",
                (rs, i) -> rs.getLong("withdrawal_order_id"), dispensingId).stream().findFirst();
    }

    public int markItemTerminatedCandidate(long itemId, Long candidateId) {
        if (candidateId == null) {
            return 0;
        }
        return jdbc.update("UPDATE dispensing_withdrawal_item SET terminated_candidate_id = ? WHERE id = ?",
                candidateId, itemId);
    }

    /** 批准/撤销同事务释放全清单占用：active_order_flag 置 NULL，任务此后可被其它撤回单引用。 */
    public int releaseAllItems(long withdrawalOrderId) {
        return jdbc.update(
                "UPDATE dispensing_withdrawal_item SET active_order_flag = NULL WHERE withdrawal_order_id = ?",
                withdrawalOrderId);
    }

    /**
     * 第一人批准：乐观条件更新，仅在仍为 PENDING 且第一批准人为空时写入并把版本号加 1。
     * 返回受影响行数；0 表示状态/版本已被并发改变，调用方据此回滚报 409。
     */
    public int markFirstApprover(long id, String approver, int expectedVersion, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing_withdrawal_order SET first_approver = ?,"
                        + " version_no = version_no + 1, updated_at_epoch = ?"
                        + " WHERE id = ? AND version_no = ? AND status = 'PENDING' AND first_approver IS NULL",
                approver, nowEpoch, id, expectedVersion);
    }

    /**
     * 双人批准终态：记录第二批准人、批准时刻，版本号加 1（乐观锁，expectedVersion 不匹配时更新 0 行）。
     */
    public int markApproved(long id, String secondApprover, int expectedVersion, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing_withdrawal_order SET second_approver = ?, status = 'APPROVED',"
                        + " approved_at_epoch = ?, version_no = version_no + 1, updated_at_epoch = ?"
                        + " WHERE id = ? AND version_no = ? AND status = 'PENDING'",
                secondApprover, nowEpoch, nowEpoch, id, expectedVersion);
    }

    public long insertApproval(long withdrawalOrderId, String approver, int approvalSeq, long approvedAtEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO dispensing_withdrawal_approval (withdrawal_order_id, approver, approval_seq,"
                            + " approved_at_epoch) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, withdrawalOrderId);
            ps.setString(2, approver);
            ps.setInt(3, approvalSeq);
            ps.setLong(4, approvedAtEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<WithdrawalApproval> findApprovals(long withdrawalOrderId) {
        return jdbc.query(
                "SELECT * FROM dispensing_withdrawal_approval WHERE withdrawal_order_id = ? ORDER BY approval_seq",
                (rs, i) -> new WithdrawalApproval(
                        rs.getLong("id"),
                        rs.getLong("withdrawal_order_id"),
                        rs.getString("approver"),
                        rs.getInt("approval_seq"),
                        rs.getLong("approved_at_epoch")),
                withdrawalOrderId);
    }

    /**
     * 未批准整体撤销终态：记录撤销幂等键与时刻，版本号加 1（乐观锁，旧 expectedVersion 更新 0 行）。
     */
    public int markCancelled(long id, String cancelRequestId, int expectedVersion, long nowEpoch) {
        return jdbc.update(
                "UPDATE dispensing_withdrawal_order SET status = 'VOID', cancel_request_id = ?,"
                        + " cancelled_at_epoch = ?, version_no = version_no + 1, updated_at_epoch = ?"
                        + " WHERE id = ? AND version_no = ? AND status = 'PENDING'",
                cancelRequestId, nowEpoch, nowEpoch, id, expectedVersion);
    }

    private WithdrawalItem mapItem(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new WithdrawalItem(
                rs.getLong("id"),
                rs.getLong("withdrawal_order_id"),
                rs.getLong("dispensing_id"),
                rs.getInt("frozen_version_no"),
                rs.getString("reason"),
                (Long) rs.getObject("terminated_candidate_id"),
                rs.getLong("created_at_epoch"));
    }

    private WithdrawalOrder mapOrder(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new WithdrawalOrder(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("mother_liquor_id"),
                rs.getString("reason"),
                WithdrawalStatus.valueOf(rs.getString("status")),
                rs.getInt("version_no"),
                rs.getString("first_approver"),
                rs.getString("second_approver"),
                (Long) rs.getObject("approved_at_epoch"),
                rs.getString("cancel_request_id"),
                (Long) rs.getObject("cancelled_at_epoch"),
                rs.getLong("created_at_epoch"),
                rs.getLong("updated_at_epoch"));
    }
}
