package com.example.starter.dispatch.repo;

import com.example.starter.dispatch.domain.OrderStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 调度令持久层：封装调度令、回执、闸门占用与告警的数据库操作。
 * 所有时间以 UTC 写入 TIMESTAMP 列；并发正确性依赖唯一索引
 * （uk_order_request、uk_occ_gate、uk_rcpt_order_seq）与条件更新。
 */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<OrderRow> ORDER_MAPPER = (rs, n) -> new OrderRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("gate_codes"),
            rs.getString("target_openings"),
            rs.getTimestamp("deadline").toInstant(),
            OrderStatus.valueOf(rs.getString("status")),
            rs.getTimestamp("issued_at").toInstant(),
            toInstant(rs.getTimestamp("accepted_at")),
            toInstant(rs.getTimestamp("completed_at")),
            toInstant(rs.getTimestamp("timeout_at")),
            toInstant(rs.getTimestamp("cancelled_at")),
            toInstant(rs.getTimestamp("aborted_at")),
            rs.getInt("escalated") == 1,
            rs.getString("aborted_detail"),
            rs.getLong("version"));

    private static final RowMapper<ReceiptRow> RECEIPT_MAPPER = (rs, n) -> {
        Boolean inPosition = null;
        int inPosValue = rs.getInt("in_position");
        if (!rs.wasNull()) {
            inPosition = inPosValue == 1;
        }
        BigDecimal deviation = rs.getBigDecimal("deviation_pct");
        if (rs.wasNull()) {
            deviation = null;
        }
        return new ReceiptRow(
                rs.getLong("id"),
                rs.getLong("order_id"),
                rs.getInt("seq_no"),
                rs.getString("gate_code"),
                rs.getBigDecimal("target_opening"),
                rs.getBigDecimal("actual_opening"),
                inPosition,
                deviation,
                rs.getInt("alarmed") == 1,
                toInstant(rs.getTimestamp("received_at")));
    };

    private static final RowMapper<AlarmRow> ALARM_MAPPER = (rs, n) -> new AlarmRow(
            rs.getLong("id"),
            rs.getLong("order_id"),
            rs.getString("alarm_type"),
            rs.getString("gate_code"),
            rs.getString("detail"),
            rs.getTimestamp("created_at").toInstant());

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    // ---------------- 调度令 ----------------

    public OrderRow findByRequestId(String requestId) {
        List<OrderRow> rows = jdbc.query(
                "SELECT * FROM dispatch_order WHERE request_id = ?", ORDER_MAPPER, requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 行级锁定读取令（须在事务内调用），用于终态竞争的串行化裁决。 */
    public OrderRow findByIdForUpdate(long id) {
        List<OrderRow> rows = jdbc.query(
                "SELECT * FROM dispatch_order WHERE id = ? FOR UPDATE", ORDER_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<OrderRow> findAllOrderById() {
        return jdbc.query("SELECT * FROM dispatch_order ORDER BY id", ORDER_MAPPER);
    }

    /** 已逾执行时限（严格晚于 deadline）且尚未终态的令（ISSUED 含未签收情形；ACCEPTED 为执行中超时）。 */
    public List<OrderRow> findExpired(Instant now) {
        return jdbc.query("SELECT * FROM dispatch_order WHERE status IN ('ISSUED','ACCEPTED') "
                        + "AND deadline < ? ORDER BY id",
                ORDER_MAPPER, Timestamp.from(now));
    }

    /**
     * 插入调度令，返回生成的主键。
     *
     * @throws DuplicateKeyException 同 requestId 并发下达
     */
    public long insertOrder(String requestId, String fingerprint, String gateCodes,
                            String targetOpenings, Instant deadline, Instant issuedAt) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO dispatch_order (request_id, fingerprint, gate_codes, target_openings, "
                            + "deadline, status, issued_at, version) VALUES (?,?,?,?,?,'ISSUED',?,0)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, gateCodes);
            ps.setString(4, targetOpenings);
            ps.setTimestamp(5, Timestamp.from(deadline));
            ps.setTimestamp(6, Timestamp.from(issuedAt));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("插入调度令未获取主键");
        }
        return key.longValue();
    }

    /** 条件更新：仅 ISSUED 可签收，返回受影响行数（0 表示并发中状态已变）。 */
    public int markAccepted(long orderId, Instant acceptedAt) {
        return jdbc.update("UPDATE dispatch_order SET status = 'ACCEPTED', accepted_at = ?, "
                        + "version = version + 1 WHERE id = ? AND status = 'ISSUED'",
                Timestamp.from(acceptedAt), orderId);
    }

    /** 条件更新：仅 ISSUED 可签收前撤销（终态）。 */
    public int markCancelled(long orderId, Instant cancelledAt) {
        return jdbc.update("UPDATE dispatch_order SET status = 'CANCELLED', cancelled_at = ?, "
                        + "version = version + 1 WHERE id = ? AND status = 'ISSUED'",
                Timestamp.from(cancelledAt), orderId);
    }

    /** 条件更新：仅 ACCEPTED 可因最后一门回执完成（与超时竞争时只允许一方生效）。 */
    public int markCompleted(long orderId, Instant completedAt) {
        return jdbc.update("UPDATE dispatch_order SET status = 'COMPLETED', completed_at = ?, "
                        + "version = version + 1 WHERE id = ? AND status = 'ACCEPTED'",
                Timestamp.from(completedAt), orderId);
    }

    /** 条件更新：仅非终态令可超时升级；由行锁与条件更新共同保证唯一终态。 */
    public int markTimeout(long orderId, Instant timeoutAt) {
        return jdbc.update("UPDATE dispatch_order SET status = 'TIMEOUT', timeout_at = ?, "
                        + "escalated = 1, version = version + 1 "
                        + "WHERE id = ? AND status IN ('ISSUED','ACCEPTED')",
                Timestamp.from(timeoutAt), orderId);
    }

    /** 条件更新：仅 ACCEPTED 可人工中止（终态），并写入已执行闸门实际开度留痕。 */
    public int markAborted(long orderId, Instant abortedAt, String abortedDetail) {
        return jdbc.update("UPDATE dispatch_order SET status = 'ABORTED', aborted_at = ?, "
                        + "aborted_detail = ?, version = version + 1 "
                        + "WHERE id = ? AND status = 'ACCEPTED'",
                Timestamp.from(abortedAt), abortedDetail, orderId);
    }

    // ---------------- 回执 ----------------

    public void insertReceipt(long orderId, int seqNo, String gateCode, BigDecimal targetOpening) {
        jdbc.update("INSERT INTO gate_receipt (order_id, seq_no, gate_code, target_opening, alarmed) "
                        + "VALUES (?,?,?,?,0)",
                orderId, seqNo, gateCode, targetOpening);
    }

    public List<ReceiptRow> findReceipts(long orderId) {
        return jdbc.query("SELECT * FROM gate_receipt WHERE order_id = ? ORDER BY seq_no",
                RECEIPT_MAPPER, orderId);
    }

    /**
     * 写入回执内容（幂等：仅 actual_opening 仍为空时写入）。
     * 返回受影响行数：1 首次写入；0 已存在回执（重放）。
     */
    public int fillReceiptIfAbsent(long receiptId, BigDecimal actualOpening, boolean inPosition,
                                   BigDecimal deviationPct, boolean alarmed, Instant receivedAt) {
        return jdbc.update("UPDATE gate_receipt SET actual_opening = ?, in_position = ?, "
                        + "deviation_pct = ?, alarmed = ?, received_at = ? "
                        + "WHERE id = ? AND actual_opening IS NULL",
                actualOpening, inPosition ? 1 : 0, deviationPct, alarmed ? 1 : 0,
                Timestamp.from(receivedAt), receiptId);
    }

    /** 未回执数量，用于判定令是否全部收齐。 */
    public int countPendingReceipts(long orderId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM gate_receipt WHERE order_id = ? AND actual_opening IS NULL",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    // ---------------- 闸门占用 ----------------

    /**
     * 逐条占用令中全部闸门；任一闸门已被占用时唯一索引冲突，事务回滚不留下部分占用。
     */
    public void insertOccupancyRows(long orderId, List<String> gateCodes, Instant now) {
        for (String gateCode : gateCodes) {
            jdbc.update("INSERT INTO gate_occupancy (gate_code, order_id, created_at) VALUES (?,?,?)",
                    gateCode, orderId, Timestamp.from(now));
        }
    }

    /** 查询给定闸门中当前正被占用的闸门编码。 */
    public List<String> findOccupiedGates(List<String> gateCodes) {
        String placeholders = String.join(",", gateCodes.stream().map(g -> "?").toList());
        return jdbc.queryForList("SELECT gate_code FROM gate_occupancy WHERE gate_code IN ("
                        + placeholders + ") ORDER BY gate_code",
                String.class, gateCodes.toArray());
    }

    /** 令进入终态时释放其占用的全部闸门。 */
    public int deleteOccupanciesByOrder(long orderId) {
        return jdbc.update("DELETE FROM gate_occupancy WHERE order_id = ?", orderId);
    }

    // ---------------- 告警 ----------------

    public void insertAlarm(long orderId, String alarmType, String gateCode, String detail, Instant now) {
        if (gateCode == null) {
            jdbc.update("INSERT INTO alarm_event (order_id, alarm_type, gate_code, detail, created_at) "
                            + "VALUES (?,?,?,?,?)",
                    orderId, alarmType, null, detail, Timestamp.from(now));
        } else {
            jdbc.update("INSERT INTO alarm_event (order_id, alarm_type, gate_code, detail, created_at) "
                            + "VALUES (?,?,?,?,?)",
                    orderId, alarmType, gateCode, detail, Timestamp.from(now));
        }
    }

    public List<AlarmRow> findAlarms(long orderId) {
        return jdbc.query("SELECT * FROM alarm_event WHERE order_id = ? ORDER BY id",
                ALARM_MAPPER, orderId);
    }

    // ---------------- 闸门主数据与开度 ----------------

    public boolean gateExists(String gateCode) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM gate WHERE code = ?",
                Integer.class, gateCode);
        return count != null && count > 0;
    }

    /** 回执到位后同步闸门当前开度（真实入口写合成主数据）。 */
    public void updateGateOpening(String gateCode, BigDecimal opening, Instant now) {
        int updated = jdbc.update("UPDATE gate SET opening = ?, updated_at = ? WHERE code = ?",
                opening, Timestamp.from(now), gateCode);
        if (updated == 0) {
            throw new IllegalStateException("闸门不存在或未更新: " + gateCode);
        }
    }
}
