package com.example.starter.dispatch.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 调度令持久层：基于 JdbcTemplate 访问 H2（MySQL 兼容模式）。
 * 所有时间字段以 UTC 毫秒时间戳（BIGINT）存储，避免时区歧义。
 */
@Repository
public class DispatchRepository {

    private static final String ORDER_COLUMNS = """
            request_id, fingerprint, status, deadline, issued_at, acknowledged_at,
            finished_at, escalated, taken_over, abort_note, payload_json, created_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public DispatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, n) -> new OrderRow(
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("status"),
            rs.getLong("deadline"),
            rs.getLong("issued_at"),
            getNullableLong(rs, "acknowledged_at"),
            getNullableLong(rs, "finished_at"),
            rs.getInt("escalated") == 1,
            rs.getInt("taken_over") == 1,
            rs.getString("abort_note"),
            rs.getString("payload_json"),
            rs.getLong("created_at"));

    private static final RowMapper<GateStepRow> STEP_MAPPER = (rs, n) -> new GateStepRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getInt("seq"),
            rs.getString("gate_code"),
            rs.getBigDecimal("target_opening"),
            rs.getBigDecimal("actual_opening"),
            rs.getBigDecimal("deviation"),
            getNullableBoolean(rs, "in_position"),
            getNullableLong(rs, "completed_at"),
            rs.getString("status"),
            rs.getString("receipt_fingerprint"));

    private static final RowMapper<NotificationRow> NOTIFICATION_MAPPER = (rs, n) ->
            new NotificationRow(
                    rs.getLong("id"),
                    rs.getString("request_id"),
                    rs.getString("type"),
                    rs.getString("message"),
                    rs.getLong("created_at"));

    private static Long getNullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Boolean getNullableBoolean(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value == 1;
    }

    public OrderRow findOrder(String requestId) {
        List<OrderRow> rows = jdbcTemplate.query(
                "SELECT " + ORDER_COLUMNS + " FROM dispatch_order WHERE request_id = ?",
                ORDER_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 行级锁读调度令（SELECT ... FOR UPDATE）：签收/撤销、回执/超时等竞态
     * 操作在同一行上串行化，以事务到达先后形成唯一结果。
     */
    public OrderRow lockOrder(String requestId) {
        List<OrderRow> rows = jdbcTemplate.query(
                "SELECT " + ORDER_COLUMNS + " FROM dispatch_order WHERE request_id = ? FOR UPDATE",
                ORDER_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<GateStepRow> findSteps(String requestId) {
        return jdbcTemplate.query(
                "SELECT * FROM gate_step WHERE request_id = ? ORDER BY seq",
                STEP_MAPPER, requestId);
    }

    public GateStepRow findStep(String requestId, String gateCode) {
        List<GateStepRow> rows = jdbcTemplate.query(
                "SELECT * FROM gate_step WHERE request_id = ? AND gate_code = ?",
                STEP_MAPPER, requestId, gateCode);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OrderRow> findAllOrders() {
        return jdbcTemplate.query(
                "SELECT " + ORDER_COLUMNS + " FROM dispatch_order ORDER BY issued_at, request_id",
                ORDER_MAPPER);
    }

    /**
     * 插入调度令与逐门执行项；调用方须在事务内先取得全部闸门占用锁。
     */
    public void insertOrder(OrderRow order, List<GateSeed> gates) {
        jdbcTemplate.update("""
                        INSERT INTO dispatch_order (request_id, fingerprint, status, deadline,
                            issued_at, acknowledged_at, finished_at, escalated, taken_over,
                            abort_note, payload_json, created_at)
                        VALUES (?, ?, ?, ?, ?, NULL, NULL, 0, 0, NULL, ?, ?)
                        """,
                order.requestId(), order.fingerprint(), order.status(), order.deadline(),
                order.issuedAt(), order.payloadJson(), order.createdAt());
        for (GateSeed gate : gates) {
            jdbcTemplate.update("""
                            INSERT INTO gate_step (request_id, seq, gate_code, target_opening,
                                actual_opening, deviation, in_position, completed_at, status,
                                receipt_fingerprint)
                            VALUES (?, ?, ?, ?, NULL, NULL, NULL, NULL, 'PENDING', NULL)
                            """,
                    order.requestId(), gate.seq(), gate.gateCode(), gate.targetOpening());
        }
    }

    /**
     * 占用闸门：主键冲突说明该闸门已有未完成调度令。
     *
     * @return 是否占用成功
     */
    public boolean acquireGateLock(String gateCode, String requestId, long now) {
        try {
            int rows = jdbcTemplate.update(
                    "INSERT INTO active_gate_lock (gate_code, request_id, locked_at) VALUES (?, ?, ?)",
                    gateCode, requestId, now);
            return rows == 1;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public int releaseGateLocks(String requestId) {
        return jdbcTemplate.update(
                "DELETE FROM active_gate_lock WHERE request_id = ?", requestId);
    }

    /**
     * 条件签收：仅 ISSUED 可签收，保证撤销/签收并发下唯一结果。
     */
    public int markAcknowledged(String requestId, long now) {
        return jdbcTemplate.update(
                "UPDATE dispatch_order SET status = 'ACKNOWLEDGED', acknowledged_at = ? "
                        + "WHERE request_id = ? AND status = 'ISSUED'",
                now, requestId);
    }

    /**
     * 条件撤销：仅未签收（ISSUED）可撤销，终态。
     */
    public int markCancelled(String requestId, long now) {
        return jdbcTemplate.update(
                "UPDATE dispatch_order SET status = 'CANCELLED', finished_at = ? "
                        + "WHERE request_id = ? AND status = 'ISSUED'",
                now, requestId);
    }

    /**
     * 条件中止：已签收且未终态（ACKNOWLEDGED）可中止，终态。
     */
    public int markAborted(String requestId, long now, String note) {
        return jdbcTemplate.update(
                "UPDATE dispatch_order SET status = 'ABORTED', finished_at = ?, abort_note = ? "
                        + "WHERE request_id = ? AND status = 'ACKNOWLEDGED'",
                now, note, requestId);
    }

    /**
     * 条件超时升级：已下达（含未签收）或已签收且未终态的令可升级。
     * 与签收/撤销/最后一门回执的终态写入互斥，保证唯一终态。
     */
    public int markTimeoutEscalated(String requestId, long now) {
        return jdbcTemplate.update(
                "UPDATE dispatch_order SET status = 'TIMEOUT_ESCALATED', finished_at = ?, "
                        + "escalated = 1 WHERE request_id = ? "
                        + "AND status IN ('ISSUED', 'ACKNOWLEDGED')",
                now, requestId);
    }

    /**
     * 条件完成：所有执行项均已回执时方可由 ACKNOWLEDGED 转 COMPLETED。
     */
    public int markCompleted(String requestId, long now) {
        return jdbcTemplate.update("""
                        UPDATE dispatch_order SET status = 'COMPLETED', finished_at = ?
                        WHERE request_id = ? AND status = 'ACKNOWLEDGED'
                          AND NOT EXISTS (
                              SELECT 1 FROM gate_step s
                              WHERE s.request_id = dispatch_order.request_id
                                AND s.status <> 'RECEIVED')
                        """,
                now, requestId);
    }

    public int markTakenOver(String requestId) {
        return jdbcTemplate.update(
                "UPDATE dispatch_order SET taken_over = 1 "
                        + "WHERE request_id = ? AND escalated = 1 AND taken_over = 0",
                requestId);
    }

    /**
     * 记录单门回执：仅 PENDING 执行项可写入，保证回执幂等与并发唯一。
     */
    public int recordReceipt(String requestId, int seq, BigDecimal actualOpening,
                             BigDecimal deviation, boolean inPosition, long completedAt,
                             String fingerprint) {
        return jdbcTemplate.update("""
                        UPDATE gate_step
                        SET actual_opening = ?, deviation = ?, in_position = ?,
                            completed_at = ?, status = 'RECEIVED', receipt_fingerprint = ?
                        WHERE request_id = ? AND seq = ? AND status = 'PENDING'
                        """,
                actualOpening, deviation, inPosition ? 1 : 0, completedAt, fingerprint,
                requestId, seq);
    }

    public int countPendingSteps(String requestId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM gate_step WHERE request_id = ? AND status <> 'RECEIVED'",
                Integer.class, requestId);
        return count == null ? 0 : count;
    }

    /**
     * 查询已过执行时限、仍未终态（已下达或已签收）的令。
     */
    public List<OrderRow> findTimeoutCandidates(long now) {
        return jdbcTemplate.query(
                "SELECT " + ORDER_COLUMNS + " FROM dispatch_order "
                        + "WHERE status IN ('ISSUED', 'ACKNOWLEDGED') AND deadline < ? "
                        + "ORDER BY deadline, request_id",
                ORDER_MAPPER, now);
    }

    public long insertNotification(String requestId, String type, String message, long now) {
        var keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO dispatch_notification (request_id, type, message, created_at) "
                            + "VALUES (?, ?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, type);
            ps.setString(3, message);
            ps.setLong(4, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public int countNotifications(String requestId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dispatch_notification WHERE request_id = ? AND type = ?",
                Integer.class, requestId, type);
        return count == null ? 0 : count;
    }

    public List<NotificationRow> findNotifications(String requestId) {
        return jdbcTemplate.query(
                "SELECT * FROM dispatch_notification WHERE request_id = ? ORDER BY id",
                NOTIFICATION_MAPPER, requestId);
    }

    /**
     * 下达时逐门执行项种子数据。
     */
    public record GateSeed(int seq, String gateCode, BigDecimal targetOpening) {
    }
}
