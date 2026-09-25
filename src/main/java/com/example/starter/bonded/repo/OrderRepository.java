package com.example.starter.bonded.repo;

import com.example.starter.bonded.domain.OrderPo;
import com.example.starter.bonded.domain.TransferStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 转关单数据访问。
 */
@Repository
public class OrderRepository {

    private static final RowMapper<OrderPo> MAPPER = (rs, n) -> new OrderPo(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("enterprise_id"),
            rs.getString("from_warehouse"),
            rs.getString("to_warehouse"),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getString("fingerprint"),
            toInstant(rs.getTimestamp("endorse_time")),
            toInstant(rs.getTimestamp("deadline")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * 新增转关单，返回生成的主键。
     */
    public Long insert(OrderPo po) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO transfer_order (request_id, enterprise_id, from_warehouse, to_warehouse,"
                            + " status, fingerprint, endorse_time, deadline, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.requestId());
            ps.setString(2, po.enterpriseId());
            ps.setString(3, po.fromWarehouse());
            ps.setString(4, po.toWarehouse());
            ps.setString(5, po.status().name());
            ps.setString(6, po.fingerprint());
            ps.setTimestamp(7, po.endorseTime() == null ? null : Timestamp.from(po.endorseTime()));
            ps.setTimestamp(8, po.deadline() == null ? null : Timestamp.from(po.deadline()));
            ps.setTimestamp(9, Timestamp.from(po.createdAt()));
            ps.setTimestamp(10, Timestamp.from(po.updatedAt()));
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    /**
     * 按 requestId 查询。
     */
    public Optional<OrderPo> findByRequestId(String requestId) {
        List<OrderPo> list = jdbc.query(
                "SELECT * FROM transfer_order WHERE request_id = ?", MAPPER, requestId);
        return list.stream().findFirst();
    }

    /**
     * 按主键查询。
     */
    public Optional<OrderPo> findById(Long id) {
        List<OrderPo> list = jdbc.query("SELECT * FROM transfer_order WHERE id = ?", MAPPER, id);
        return list.stream().findFirst();
    }

    /**
     * 按主键加行锁查询（SELECT ... FOR UPDATE），须在事务内调用。
     */
    public Optional<OrderPo> findByIdForUpdate(Long id) {
        List<OrderPo> list = jdbc.query("SELECT * FROM transfer_order WHERE id = ? FOR UPDATE", MAPPER, id);
        return list.stream().findFirst();
    }

    /**
     * 条件更新状态：仅当当前状态等于期望状态时生效，返回受影响行数（0 表示并发冲突或状态已变迁）。
     */
    public int updateStatusIfCurrent(Long id, TransferStatus expected, TransferStatus next, Instant updatedAt) {
        return jdbc.update(
                "UPDATE transfer_order SET status = ?, updated_at = ? WHERE id = ? AND status = ?",
                next.name(), Timestamp.from(updatedAt), id, expected.name());
    }

    /**
     * 核注：仅当当前状态为 APPLIED 时写入核注时间、截止时间并迁移状态，返回受影响行数。
     */
    public int endorseIfApplied(Long id, TransferStatus next, Instant endorseTime, Instant deadline, Instant updatedAt) {
        return jdbc.update(
                "UPDATE transfer_order SET status = ?, endorse_time = ?, deadline = ?, updated_at = ?"
                        + " WHERE id = ? AND status = 'APPLIED'",
                next.name(), Timestamp.from(endorseTime), Timestamp.from(deadline),
                Timestamp.from(updatedAt), id);
    }

    /**
     * 海关复核解除异常：仅当当前状态为 ABNORMAL 时迁移状态并重置核销截止时间，返回受影响行数。
     */
    public int resolveAbnormal(Long id, TransferStatus next, Instant newDeadline, Instant updatedAt) {
        return jdbc.update(
                "UPDATE transfer_order SET status = ?, deadline = ?, updated_at = ? WHERE id = ? AND status = 'ABNORMAL'",
                next.name(), Timestamp.from(newDeadline), Timestamp.from(updatedAt), id);
    }

    /**
     * 查询某企业处于指定状态集合的单据。
     */
    public List<OrderPo> findByEnterpriseAndStatusIn(String enterpriseId, List<TransferStatus> statuses) {
        String placeholders = String.join(",", statuses.stream().map(s -> "?").toList());
        Object[] args = new Object[statuses.size() + 1];
        args[0] = enterpriseId;
        for (int i = 0; i < statuses.size(); i++) {
            args[i + 1] = statuses.get(i).name();
        }
        return jdbc.query(
                "SELECT * FROM transfer_order WHERE enterprise_id = ? AND status IN (" + placeholders + ")",
                MAPPER, args);
    }

    /**
     * 查询已超时（deadline <= now）且仍处于在途/已核注状态的单据。
     */
    public List<OrderPo> findExpired(Instant now) {
        return jdbc.query(
                "SELECT * FROM transfer_order WHERE deadline IS NOT NULL AND deadline <= ?"
                        + " AND status IN ('ENDORSED','IN_TRANSIT')",
                MAPPER, Timestamp.from(now));
    }
}
