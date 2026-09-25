package com.example.starter.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 强平指令与平仓明细数据访问。指令执行后（DONE）或撤销后（CANCELLED）为终态，不可再变更。
 */
@Repository
public class LiquidationRepository {

    /** 强平指令行。 */
    public record OrderRow(long id, long accountId, Long marginCallId, String reason, String status) {
    }

    /** 平仓明细行。amount 单位：分。 */
    public record ItemRow(long id, long orderId, long liabilityId, long amount) {
    }

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, i) -> new OrderRow(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getObject("margin_call_id") == null ? null : rs.getLong("margin_call_id"),
            rs.getString("reason"),
            rs.getString("status"));

    private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, i) -> new ItemRow(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getLong("liability_id"),
            rs.getLong("amount"));

    private final JdbcTemplate jdbc;

    public LiquidationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertOrder(long accountId, Long marginCallId, String reason, LocalDateTime createdAt) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO liquidation_order (account_id, margin_call_id, reason, status, created_at)"
                            + " VALUES (?, ?, ?, 'PENDING', ?)",
                    new String[]{"id"});
            ps.setLong(1, accountId);
            if (marginCallId == null) {
                ps.setNull(2, java.sql.Types.BIGINT);
            } else {
                ps.setLong(2, marginCallId);
            }
            ps.setString(3, reason);
            ps.setTimestamp(4, Timestamp.valueOf(createdAt));
            return ps;
        }, kh);
        return Objects.requireNonNull(kh.getKey()).longValue();
    }

    public Optional<OrderRow> findOrderById(long id) {
        return jdbc.query("SELECT * FROM liquidation_order WHERE id = ?", ORDER_MAPPER, id)
                .stream().findFirst();
    }

    public List<OrderRow> findPendingByAccount(long accountId) {
        return jdbc.query("SELECT * FROM liquidation_order WHERE account_id = ? AND status = 'PENDING'"
                + " ORDER BY id ASC", ORDER_MAPPER, accountId);
    }

    /** 仅当指令仍处于 PENDING 时流转为目标状态；返回是否流转成功（终态不可撤销）。 */
    public boolean transitionIfPending(long orderId, String targetStatus, LocalDateTime closedAt) {
        return jdbc.update("UPDATE liquidation_order SET status = ?, closed_at = ?"
                        + " WHERE id = ? AND status = 'PENDING'",
                targetStatus, Timestamp.valueOf(closedAt), orderId) == 1;
    }

    public void insertItem(long orderId, long liabilityId, long amountCents) {
        jdbc.update("INSERT INTO liquidation_item (order_id, liability_id, amount) VALUES (?, ?, ?)",
                orderId, liabilityId, amountCents);
    }

    public List<ItemRow> findItemsByOrder(long orderId) {
        return jdbc.query("SELECT * FROM liquidation_item WHERE order_id = ? ORDER BY id ASC",
                ITEM_MAPPER, orderId);
    }
}
