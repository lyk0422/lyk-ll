package com.example.starter.flood.repo;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 泄流指令仓储及指令链事件流。
 */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbcTemplate;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public static final RowMapper<OrderPO> MAPPER = new RowMapper<>() {
        @Override
        public OrderPO mapRow(ResultSet rs, int rowNum) throws SQLException {
            OrderPO po = new OrderPO();
            po.id = rs.getLong("id");
            po.orderNo = rs.getString("order_no");
            po.planId = rs.getLong("plan_id");
            po.status = rs.getString("status");
            po.issuedAt = toLdt(rs.getTimestamp("issued_at"));
            po.signDeadline = toLdt(rs.getTimestamp("sign_deadline"));
            po.signedAt = toLdt(rs.getTimestamp("signed_at"));
            po.signer = rs.getString("signer");
            po.escalatedAt = toLdt(rs.getTimestamp("escalated_at"));
            po.escalatedTo = rs.getString("escalated_to");
            po.executedAt = toLdt(rs.getTimestamp("executed_at"));
            po.executor = rs.getString("executor");
            po.receiptedAt = toLdt(rs.getTimestamp("receipted_at"));
            po.actualOpenings = rs.getString("actual_openings");
            po.actualTotalRelease = rs.getBigDecimal("actual_total_release");
            po.deviationRatio = rs.getBigDecimal("deviation_ratio");
            po.deviationReason = rs.getString("deviation_reason");
            po.receiptRequestId = rs.getString("receipt_request_id");
            return po;
        }
    };

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    public void insert(OrderPO po) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO release_order(order_no, plan_id, status, issued_at, sign_deadline, "
                            + "signed_at, signer, escalated_at, escalated_to, executed_at, executor, "
                            + "receipted_at, actual_openings, actual_total_release, deviation_ratio, "
                            + "deviation_reason, receipt_request_id) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, po.orderNo);
            ps.setLong(2, po.planId);
            ps.setString(3, po.status);
            ps.setTimestamp(4, Timestamp.valueOf(po.issuedAt));
            ps.setTimestamp(5, Timestamp.valueOf(po.signDeadline));
            ps.setTimestamp(6, po.signedAt == null ? null : Timestamp.valueOf(po.signedAt));
            ps.setString(7, po.signer);
            ps.setTimestamp(8, po.escalatedAt == null ? null : Timestamp.valueOf(po.escalatedAt));
            ps.setString(9, po.escalatedTo);
            ps.setTimestamp(10, po.executedAt == null ? null : Timestamp.valueOf(po.executedAt));
            ps.setString(11, po.executor);
            ps.setTimestamp(12, po.receiptedAt == null ? null : Timestamp.valueOf(po.receiptedAt));
            ps.setString(13, po.actualOpenings);
            ps.setBigDecimal(14, po.actualTotalRelease);
            ps.setBigDecimal(15, po.deviationRatio);
            ps.setString(16, po.deviationReason);
            ps.setString(17, po.receiptRequestId);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key != null) {
            po.id = key.longValue();
        }
    }

    public OrderPO findByNo(String orderNo) {
        List<OrderPO> list = jdbcTemplate.query(
                "SELECT * FROM release_order WHERE order_no = ?", MAPPER, orderNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public OrderPO findByPlanId(Long planId) {
        List<OrderPO> list = jdbcTemplate.query(
                "SELECT * FROM release_order WHERE plan_id = ?", MAPPER, planId);
        return list.isEmpty() ? null : list.get(0);
    }

    public OrderPO findByReceiptRequestId(String requestId) {
        List<OrderPO> list = jdbcTemplate.query(
                "SELECT * FROM release_order WHERE receipt_request_id = ?", MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    public void addEvent(Long orderId, String eventType, LocalDateTime eventTime, String detail) {
        jdbcTemplate.update(
                "INSERT INTO order_event(order_id, event_type, event_time, detail) VALUES (?,?,?,?)",
                orderId, eventType, Timestamp.valueOf(eventTime), detail);
    }

    /**
     * 签收：仅 ISSUED 态可签收；自动升级态(ESCALATED)允许值班领导补签收，
     * 但“恰超时”的升级事实已记录不可撤销。
     */
    public int sign(Long orderId, String signer, LocalDateTime signedAt) {
        return jdbcTemplate.update(
                "UPDATE release_order SET status = 'SIGNED', signed_at = ?, signer = ? "
                        + "WHERE id = ? AND status IN ('ISSUED','ESCALATED')",
                Timestamp.valueOf(signedAt), signer, orderId);
    }

    /**
     * 超时升级：仅仍在等待签收(ISSUED)的指令可升级。
     */
    public int escalate(Long orderId, String escalatedTo, LocalDateTime escalatedAt) {
        return jdbcTemplate.update(
                "UPDATE release_order SET status = 'ESCALATED', escalated_at = ?, escalated_to = ? "
                        + "WHERE id = ? AND status = 'ISSUED'",
                Timestamp.valueOf(escalatedAt), escalatedTo, orderId);
    }

    /**
     * 升级态补签收：填写签收人与签收时刻，状态保留 ESCALATED（升级事实不撤销）。
     */
    public int signKeepEscalated(Long orderId, String signer, LocalDateTime signedAt) {
        return jdbcTemplate.update(
                "UPDATE release_order SET signed_at = ?, signer = ? "
                        + "WHERE id = ? AND status = 'ESCALATED' AND signed_at IS NULL",
                Timestamp.valueOf(signedAt), signer, orderId);
    }

    /**
     * 执行：已签收可执行；超时升级态必须已补签收（signed_at 非空）才可执行。
     */
    public int execute(Long orderId, String executor, LocalDateTime executedAt) {
        return jdbcTemplate.update(
                "UPDATE release_order SET status = 'EXECUTING', executed_at = ?, executor = ? "
                        + "WHERE id = ? AND (status = 'SIGNED' "
                        + "OR (status = 'ESCALATED' AND signed_at IS NOT NULL))",
                Timestamp.valueOf(executedAt), executor, orderId);
    }

    /**
     * 回执：仅执行中（已签收并开闸）的指令可闭环。
     */
    public int receipt(Long orderId, String receiptRequestId, String actualOpenings,
                       BigDecimal actualTotalRelease, BigDecimal deviationRatio,
                       String deviationReason, LocalDateTime receiptedAt) {
        return jdbcTemplate.update(
                "UPDATE release_order SET status = 'RECEIPTED', receipted_at = ?, "
                        + "actual_openings = ?, actual_total_release = ?, deviation_ratio = ?, "
                        + "deviation_reason = ?, receipt_request_id = ? "
                        + "WHERE id = ? AND status = 'EXECUTING'",
                Timestamp.valueOf(receiptedAt), actualOpenings, actualTotalRelease,
                deviationRatio, deviationReason, receiptRequestId, orderId);
    }
}
