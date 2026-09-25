package com.example.starter.transfer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.example.starter.transfer.TransferDtos.HistoryEntry;
import com.example.starter.transfer.TransferDtos.ItemView;

/**
 * 转关服务数据访问层：全部使用参数化 SQL。
 * 行级锁（SELECT ... FOR UPDATE）用于核注/核销/撤销的并发互斥。
 */
@Repository
public class TransferRepository {

    /** 转关单行记录。 */
    public record OrderRow(
            long id,
            String requestId,
            String fingerprint,
            String enterpriseId,
            String fromWarehouse,
            String toWarehouse,
            TransferStatus status,
            int version,
            Instant endorsedAt,
            Instant verifyDeadline,
            Instant createdAt) {
    }

    /** 商品项行记录。 */
    public record ItemRow(
            long id,
            long orderId,
            String tariffCode,
            long quantity,
            java.math.BigDecimal unitPrice,
            long verifiedQuantity) {
    }

    /** 核销幂等记录。 */
    public record VerificationRow(long id, String requestId, String fingerprint, long orderId, String responseBody) {
    }

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, n) -> new OrderRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("enterprise_id"),
            rs.getString("from_warehouse"),
            rs.getString("to_warehouse"),
            TransferStatus.valueOf(rs.getString("status")),
            rs.getInt("version"),
            toInstant(rs, "endorsed_at"),
            toInstant(rs, "verify_deadline"),
            toInstant(rs, "created_at"));

    private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, n) -> new ItemRow(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("tariff_code"),
            rs.getLong("quantity"),
            rs.getBigDecimal("unit_price"),
            rs.getLong("verified_quantity"));

    private final JdbcTemplate jdbc;

    public TransferRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    // ---------- 转关单 ----------

    public void insertOrder(OrderRow row) {
        jdbc.update("""
                INSERT INTO transfer_order
                (request_id, fingerprint, enterprise_id, from_warehouse, to_warehouse,
                 status, version, endorsed_at, verify_deadline, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """,
                row.requestId(), row.fingerprint(), row.enterpriseId(),
                row.fromWarehouse(), row.toWarehouse(), row.status().name(), row.version(),
                ts(row.endorsedAt()), ts(row.verifyDeadline()), ts(row.createdAt()), ts(row.createdAt()));
    }

    public Optional<OrderRow> findOrderById(long id) {
        return jdbc.query("SELECT * FROM transfer_order WHERE id = ?", ORDER_MAPPER, id)
                .stream().findFirst();
    }

    public Optional<OrderRow> findOrderByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM transfer_order WHERE request_id = ?", ORDER_MAPPER, requestId)
                .stream().findFirst();
    }

    /** 行级锁读取单据，用于核注/核销/撤销的并发互斥。 */
    public Optional<OrderRow> lockOrderById(long id) {
        return jdbc.query("SELECT * FROM transfer_order WHERE id = ? FOR UPDATE", ORDER_MAPPER, id)
                .stream().findFirst();
    }

    /** 更新状态并递增版本号，返回受影响行数。 */
    public int updateOrderStatus(long id, TransferStatus status, Instant endorsedAt,
            Instant verifyDeadline, Instant updatedAt) {
        return jdbc.update("""
                UPDATE transfer_order
                SET status = ?, version = version + 1, endorsed_at = ?, verify_deadline = ?, updated_at = ?
                WHERE id = ?
                """, status.name(), ts(endorsedAt), ts(verifyDeadline), ts(updatedAt), id);
    }

    public List<OrderRow> findExpiredInTransit(Instant now) {
        return jdbc.query("""
                SELECT * FROM transfer_order
                WHERE status = 'IN_TRANSIT' AND verify_deadline <= ?
                ORDER BY id
                """, ORDER_MAPPER, ts(now));
    }

    public List<OrderRow> findByEnterpriseAndStatus(String enterpriseId, TransferStatus status) {
        return jdbc.query("""
                SELECT * FROM transfer_order
                WHERE enterprise_id = ? AND status = ?
                ORDER BY id
                """, ORDER_MAPPER, enterpriseId, status.name());
    }

    // ---------- 商品项 ----------

    public void insertItem(long orderId, String tariffCode, long quantity, java.math.BigDecimal unitPrice) {
        jdbc.update("""
                INSERT INTO transfer_order_item (order_id, tariff_code, quantity, unit_price, verified_quantity)
                VALUES (?,?,?,?,0)
                """, orderId, tariffCode, quantity, unitPrice);
    }

    public List<ItemRow> findItems(long orderId) {
        return jdbc.query("SELECT * FROM transfer_order_item WHERE order_id = ? ORDER BY tariff_code",
                ITEM_MAPPER, orderId);
    }

    public void addVerifiedQuantity(long itemId, long delta) {
        jdbc.update("""
                UPDATE transfer_order_item
                SET verified_quantity = verified_quantity + ?
                WHERE id = ?
                """, delta, itemId);
    }

    // ---------- 库存 ----------

    /** 行级锁读取库存行。 */
    public Optional<Long> lockStock(String warehouse, String tariffCode) {
        return jdbc.query("""
                SELECT quantity FROM warehouse_stock
                WHERE warehouse_code = ? AND tariff_code = ? FOR UPDATE
                """, (rs, n) -> rs.getLong(1), warehouse, tariffCode).stream().findFirst();
    }

    public void decreaseStock(String warehouse, String tariffCode, long delta) {
        jdbc.update("""
                UPDATE warehouse_stock SET quantity = quantity - ?
                WHERE warehouse_code = ? AND tariff_code = ?
                """, delta, warehouse, tariffCode);
    }

    public void increaseStock(String warehouse, String tariffCode, long delta) {
        jdbc.update("""
                UPDATE warehouse_stock SET quantity = quantity + ?
                WHERE warehouse_code = ? AND tariff_code = ?
                """, delta, warehouse, tariffCode);
    }

    // ---------- 核销幂等 ----------

    public Optional<VerificationRow> findVerification(String requestId) {
        return jdbc.query("""
                SELECT id, request_id, fingerprint, order_id, response_body
                FROM transfer_verification WHERE request_id = ?
                """, (rs, n) -> new VerificationRow(rs.getLong(1), rs.getString(2),
                        rs.getString(3), rs.getLong(4), rs.getString(5)), requestId)
                .stream().findFirst();
    }

    public void insertVerification(String requestId, String fingerprint, long orderId,
            String responseBody, Instant createdAt) {
        jdbc.update("""
                INSERT INTO transfer_verification (request_id, fingerprint, order_id, response_body, created_at)
                VALUES (?,?,?,?,?)
                """, requestId, fingerprint, orderId, responseBody, ts(createdAt));
    }

    // ---------- 状态留痕 ----------

    public void insertHistory(long orderId, int seq, TransferStatus from, TransferStatus to,
            Instant changedAt, String note) {
        jdbc.update("""
                INSERT INTO transfer_status_history (order_id, seq, from_status, to_status, changed_at, note)
                VALUES (?,?,?,?,?,?)
                """, orderId, seq, from == null ? null : from.name(), to.name(), ts(changedAt), note);
    }

    public List<HistoryEntry> findHistory(long orderId) {
        return jdbc.query("""
                SELECT seq, from_status, to_status, changed_at, note
                FROM transfer_status_history WHERE order_id = ? ORDER BY seq
                """, (rs, n) -> new HistoryEntry(rs.getInt("seq"), rs.getString("from_status"),
                        rs.getString("to_status"), toInstant(rs, "changed_at"), rs.getString("note")),
                orderId);
    }

    // ---------- 企业账户 ----------

    public boolean isEnterpriseFrozen(String enterpriseId) {
        List<Boolean> rows = jdbc.query("""
                SELECT frozen FROM enterprise_account WHERE enterprise_id = ?
                """, (rs, n) -> rs.getBoolean(1), enterpriseId);
        return !rows.isEmpty() && rows.get(0);
    }

    public void setEnterpriseFrozen(String enterpriseId, boolean frozen, Instant updatedAt) {
        int updated = jdbc.update("""
                UPDATE enterprise_account SET frozen = ?, updated_at = ? WHERE enterprise_id = ?
                """, frozen, ts(updatedAt), enterpriseId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO enterprise_account (enterprise_id, frozen, updated_at) VALUES (?,?,?)
                    """, enterpriseId, frozen, ts(updatedAt));
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
