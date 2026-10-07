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
 * 撤回单创建、批准与撤销均在事务内先对撤回单行加 FOR UPDATE 行锁，再按分装主键升序锁分装行，
 * 与发药/候选生效路径（先分装行后候选行）共同构成无环锁顺序，避免死锁。
 */
@Repository
public class WithdrawalRepository {

    private final JdbcTemplate jdbc;

    public WithdrawalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertOrder(String requestId, String fingerprint, String contentFingerprint, long motherLiquorId,
                            String reason, long nowEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO withdrawal_order (request_id, fingerprint, content_fingerprint, mother_liquor_id,"
                            + " status, reason, version_no, created_at_epoch, updated_at_epoch)"
                            + " VALUES (?, ?, ?, ?, 'PENDING', ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, contentFingerprint);
            ps.setLong(4, motherLiquorId);
            if (reason == null) {
                ps.setNull(5, java.sql.Types.VARCHAR);
            } else {
                ps.setString(5, reason);
            }
            ps.setLong(6, nowEpoch);
            ps.setLong(7, nowEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public Optional<WithdrawalOrder> findById(long id) {
        return jdbc.query("SELECT * FROM withdrawal_order WHERE id = ?", this::map, id).stream().findFirst();
    }

    /** 撤回单行锁；撤回事务内最先获取，串行化同单的并发批准/撤销。 */
    public Optional<WithdrawalOrder> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM withdrawal_order WHERE id = ? FOR UPDATE", this::map, id).stream()
                .findFirst();
    }

    public Optional<WithdrawalOrder> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM withdrawal_order WHERE request_id = ?", this::map, requestId).stream()
                .findFirst();
    }

    public Optional<WithdrawalOrder> findByCancelRequestId(String cancelRequestId) {
        return jdbc.query("SELECT * FROM withdrawal_order WHERE cancel_request_id = ?", this::map, cancelRequestId)
                .stream().findFirst();
    }

    public long insertItem(long withdrawalId, long dispensingId, int frozenVersionNo, String reason,
                           long nowEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO withdrawal_item (withdrawal_id, dispensing_id, frozen_version_no, reason,"
                            + " created_at_epoch) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, withdrawalId);
            ps.setLong(2, dispensingId);
            ps.setInt(3, frozenVersionNo);
            ps.setString(4, reason);
            ps.setLong(5, nowEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 冻结清单，按分装主键升序返回（与加锁顺序一致）。 */
    public List<WithdrawalItem> findItems(long withdrawalId) {
        return jdbc.query(
                "SELECT * FROM withdrawal_item WHERE withdrawal_id = ? ORDER BY dispensing_id",
                (rs, i) -> new WithdrawalItem(
                        rs.getLong("id"),
                        rs.getLong("withdrawal_id"),
                        rs.getLong("dispensing_id"),
                        rs.getInt("frozen_version_no"),
                        rs.getString("reason"),
                        rs.getLong("created_at_epoch")),
                withdrawalId);
    }

    public long insertApproval(long withdrawalId, String approver, int approveSeq, long nowEpoch) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO withdrawal_approval (withdrawal_id, approver, approve_seq, approved_at_epoch)"
                            + " VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, withdrawalId);
            ps.setString(2, approver);
            ps.setInt(3, approveSeq);
            ps.setLong(4, nowEpoch);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    public List<WithdrawalApproval> findApprovals(long withdrawalId) {
        return jdbc.query(
                "SELECT * FROM withdrawal_approval WHERE withdrawal_id = ? ORDER BY approve_seq",
                (rs, i) -> new WithdrawalApproval(
                        rs.getLong("id"),
                        rs.getLong("withdrawal_id"),
                        rs.getString("approver"),
                        rs.getInt("approve_seq"),
                        rs.getLong("approved_at_epoch")),
                withdrawalId);
    }

    /** 第一名批准：乐观版本 expectedVersion 匹配才写入并把版本加 1；调用方已持行锁。 */
    public int markFirstApprover(long id, String approver, int expectedVersion, long nowEpoch) {
        return jdbc.update(
                "UPDATE withdrawal_order SET first_approver = ?, version_no = version_no + 1, updated_at_epoch = ?"
                        + " WHERE id = ? AND status = 'PENDING' AND version_no = ? AND first_approver IS NULL",
                approver, nowEpoch, id, expectedVersion);
    }

    /**
     * 第二名不同人员批准完成：状态置 APPROVED 并版本加 1；
     * 仅在状态仍为 PENDING 且乐观版本匹配时生效，调用方已持行锁并完成全清单最终重查。
     */
    public int markApproved(long id, String secondApprover, int expectedVersion, long nowEpoch) {
        return jdbc.update(
                "UPDATE withdrawal_order SET second_approver = ?, status = 'APPROVED', approved_at_epoch = ?,"
                        + " version_no = version_no + 1, updated_at_epoch = ?"
                        + " WHERE id = ? AND status = 'PENDING' AND version_no = ? AND first_approver IS NOT NULL"
                        + " AND second_approver IS NULL",
                secondApprover, nowEpoch, nowEpoch, id, expectedVersion);
    }

    /** 未批准整体撤销：状态置 CANCELLED、记录撤销幂等键并版本加 1；已批准不匹配 PENDING 不会被更新。 */
    public int markCancelled(long id, String cancelRequestId, int expectedVersion, long nowEpoch) {
        return jdbc.update(
                "UPDATE withdrawal_order SET status = 'CANCELLED', cancel_request_id = ?, cancelled_at_epoch = ?,"
                        + " version_no = version_no + 1, updated_at_epoch = ?"
                        + " WHERE id = ? AND status = 'PENDING' AND version_no = ?",
                cancelRequestId, nowEpoch, nowEpoch, id, expectedVersion);
    }

    private WithdrawalOrder map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        Long approvedAt = (Long) rs.getObject("approved_at_epoch");
        Long cancelledAt = (Long) rs.getObject("cancelled_at_epoch");
        return new WithdrawalOrder(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("content_fingerprint"),
                rs.getLong("mother_liquor_id"),
                WithdrawalStatus.valueOf(rs.getString("status")),
                rs.getString("reason"),
                rs.getString("first_approver"),
                rs.getString("second_approver"),
                approvedAt,
                rs.getString("cancel_request_id"),
                cancelledAt,
                rs.getInt("version_no"),
                rs.getLong("created_at_epoch"),
                rs.getLong("updated_at_epoch"));
    }
}
