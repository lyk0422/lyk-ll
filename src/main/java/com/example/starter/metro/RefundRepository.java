package com.example.starter.metro;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 退款冲正数据访问：退款单、调账批次、调整单与调整明细。
 * 只新增独立冲正表，不改写原交易流水、清分明细与封账合计。
 */
@Repository
public class RefundRepository {

    private final JdbcTemplate jdbcTemplate;

    public RefundRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<RefundRow> REFUND_MAPPER = (rs, rowNum) -> new RefundRow(
            rs.getLong("id"),
            rs.getString("refund_no"),
            rs.getString("txn_no"),
            rs.getLong("amount_cents"),
            rs.getString("reason"),
            rs.getString("applicant"),
            rs.getString("status"),
            rs.getString("reviewer1"),
            rs.getString("reviewer2"),
            rs.getInt("version"),
            rs.getString("request_id"),
            rs.getString("fingerprint"));

    private static final RowMapper<AdjustBatchRow> ADJUST_BATCH_MAPPER = (rs, rowNum) -> new AdjustBatchRow(
            rs.getLong("id"),
            rs.getString("status"),
            rs.getLong("total_cents"),
            rs.getInt("order_count"),
            rs.getInt("version"),
            rs.getString("seal_request_id"),
            toDateTime(rs, "sealed_at"));

    private static final RowMapper<AdjustOrderRow> ADJUST_ORDER_MAPPER = (rs, rowNum) -> new AdjustOrderRow(
            rs.getLong("id"),
            rs.getString("adjust_no"),
            rs.getString("refund_no"),
            rs.getString("txn_no"),
            rs.getLong("batch_id"),
            rs.getLong("amount_cents"));

    private static final RowMapper<AdjustDetailRow> ADJUST_DETAIL_MAPPER = (rs, rowNum) -> new AdjustDetailRow(
            rs.getLong("id"),
            rs.getLong("adjustment_id"),
            rs.getString("txn_no"),
            rs.getString("line_code"),
            rs.getLong("amount_cents"));

    private static final RowMapper<LineAmountRow> LINE_AMOUNT_MAPPER = (rs, rowNum) -> new LineAmountRow(
            rs.getString("line_code"),
            rs.getLong("amount_cents"));

    private static LocalDateTime toDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static final String REFUND_COLUMNS =
            "id, refund_no, txn_no, amount_cents, reason, applicant, status, reviewer1, reviewer2,"
            + " version, request_id, fingerprint";

    /** 插入退款单（refund_no 先放唯一占位值），返回主键。 */
    public long insertRefund(RefundRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO refund_order (refund_no, txn_no, amount_cents, reason, applicant, status,"
                    + " reviewer1, reviewer2, version, request_id, fingerprint, created_at, updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.refundNo());
            ps.setString(2, row.txnNo());
            ps.setLong(3, row.amountCents());
            ps.setString(4, row.reason());
            ps.setString(5, row.applicant());
            ps.setString(6, row.status());
            ps.setString(7, row.reviewer1());
            ps.setString(8, row.reviewer2());
            ps.setInt(9, row.version());
            ps.setString(10, row.requestId());
            ps.setString(11, row.fingerprint());
            ps.setTimestamp(12, Timestamp.valueOf(now));
            ps.setTimestamp(13, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按单号查询退款单。 */
    public Optional<RefundRow> findRefundByNo(String refundNo) {
        List<RefundRow> rows = jdbcTemplate.query(
                "SELECT " + REFUND_COLUMNS + " FROM refund_order WHERE refund_no = ?",
                REFUND_MAPPER, refundNo);
        return rows.stream().findFirst();
    }

    /** 按单号查询退款单并加行锁（SELECT ... FOR UPDATE）。 */
    public Optional<RefundRow> findRefundByNoForUpdate(String refundNo) {
        List<RefundRow> rows = jdbcTemplate.query(
                "SELECT " + REFUND_COLUMNS + " FROM refund_order WHERE refund_no = ? FOR UPDATE",
                REFUND_MAPPER, refundNo);
        return rows.stream().findFirst();
    }

    /** 双人确认生效：登记两名复核人、置终态并递增版本。 */
    public void confirmRefund(long id, String reviewer1, String reviewer2, int newVersion) {
        jdbcTemplate.update(
                "UPDATE refund_order SET status = 'CONFIRMED', reviewer1 = ?, reviewer2 = ?, version = ?,"
                + " updated_at = ? WHERE id = ?",
                reviewer1, reviewer2, newVersion, Timestamp.valueOf(LocalDateTime.now()), id);
    }

    /** 查询当前开放调账批次并加行锁；全局最多一个开放批次。 */
    public Optional<AdjustBatchRow> findOpenAdjustBatchForUpdate() {
        List<AdjustBatchRow> rows = jdbcTemplate.query(
                "SELECT id, status, total_cents, order_count, version, seal_request_id, sealed_at"
                + " FROM adjustment_batch WHERE open_flag = 1 FOR UPDATE",
                ADJUST_BATCH_MAPPER);
        return rows.stream().findFirst();
    }

    /** 创建开放调账批次（open_flag = 1，版本从 1 开始），返回主键。 */
    public long insertAdjustBatch() {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO adjustment_batch (status, open_flag, total_cents, order_count, version,"
                    + " created_at) VALUES ('OPEN', 1, 0, 0, 1, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setTimestamp(1, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 按主键查询调账批次。 */
    public Optional<AdjustBatchRow> findAdjustBatchById(long batchId) {
        List<AdjustBatchRow> rows = jdbcTemplate.query(
                "SELECT id, status, total_cents, order_count, version, seal_request_id, sealed_at"
                + " FROM adjustment_batch WHERE id = ?",
                ADJUST_BATCH_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /** 按主键查询调账批次并加行锁。 */
    public Optional<AdjustBatchRow> findAdjustBatchByIdForUpdate(long batchId) {
        List<AdjustBatchRow> rows = jdbcTemplate.query(
                "SELECT id, status, total_cents, order_count, version, seal_request_id, sealed_at"
                + " FROM adjustment_batch WHERE id = ? FOR UPDATE",
                ADJUST_BATCH_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /** 封存调账批次：快照合计与笔数、递增版本、撤下开放标记（终态只读）。 */
    public void sealAdjustBatch(long batchId, long totalCents, int orderCount, int newVersion,
                                String sealRequestId, LocalDateTime sealedAt) {
        jdbcTemplate.update(
                "UPDATE adjustment_batch SET status = 'SEALED', open_flag = NULL, total_cents = ?,"
                + " order_count = ?, version = ?, seal_request_id = ?, sealed_at = ? WHERE id = ?",
                totalCents, orderCount, newVersion, sealRequestId, Timestamp.valueOf(sealedAt), batchId);
    }

    /** 插入调整单，返回主键。 */
    public long insertAdjustOrder(AdjustOrderRow row) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO adjustment_order (adjust_no, refund_no, txn_no, batch_id, amount_cents,"
                    + " created_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, row.adjustNo());
            ps.setString(2, row.refundNo());
            ps.setString(3, row.txnNo());
            ps.setLong(4, row.batchId());
            ps.setLong(5, row.amountCents());
            ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 查询批次内全部调整单（封存计额与证据展示）。 */
    public List<AdjustOrderRow> findAdjustOrdersByBatch(long batchId) {
        return jdbcTemplate.query(
                "SELECT id, adjust_no, refund_no, txn_no, batch_id, amount_cents FROM adjustment_order"
                + " WHERE batch_id = ? ORDER BY id",
                ADJUST_ORDER_MAPPER, batchId);
    }

    /** 插入调整明细（负向金额）。 */
    public void insertAdjustDetail(AdjustDetailRow row) {
        jdbcTemplate.update(
                "INSERT INTO adjustment_detail (adjustment_id, txn_no, line_code, amount_cents, created_at)"
                + " VALUES (?,?,?,?,?)",
                row.adjustmentId(), row.txnNo(), row.lineCode(), row.amountCents(),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 查询调整单的线路明细。 */
    public List<AdjustDetailRow> findAdjustDetailsByAdjustment(long adjustmentId) {
        return jdbcTemplate.query(
                "SELECT id, adjustment_id, txn_no, line_code, amount_cents FROM adjustment_detail"
                + " WHERE adjustment_id = ? ORDER BY id",
                ADJUST_DETAIL_MAPPER, adjustmentId);
    }

    /** 原清分明细中该行程各线路实收份额（NORMAL/SETTLE 汇总，暂记与冲销互抵不计）。 */
    public List<LineAmountRow> findOriginalShares(String txnNo) {
        return jdbcTemplate.query(
                "SELECT line_code, SUM(amount_cents) AS amount_cents FROM clearing_detail"
                + " WHERE txn_no = ? AND entry_type IN ('NORMAL','SETTLE') GROUP BY line_code",
                LINE_AMOUNT_MAPPER, txnNo);
    }

    /** 该行程各线路累计已退金额（正数口径，取自调整明细绝对值）。 */
    public List<LineAmountRow> findRefundedByLine(String txnNo) {
        return jdbcTemplate.query(
                "SELECT line_code, -SUM(amount_cents) AS amount_cents FROM adjustment_detail"
                + " WHERE txn_no = ? GROUP BY line_code",
                LINE_AMOUNT_MAPPER, txnNo);
    }

    /**
     * 退款单行。
     */
    public record RefundRow(Long id, String refundNo, String txnNo, long amountCents, String reason,
                            String applicant, String status, String reviewer1, String reviewer2,
                            int version, String requestId, String fingerprint) {
    }

    /**
     * 调账批次行。
     */
    public record AdjustBatchRow(long id, String status, long totalCents, int orderCount, int version,
                                 String sealRequestId, LocalDateTime sealedAt) {
    }

    /**
     * 调整单行。
     */
    public record AdjustOrderRow(long id, String adjustNo, String refundNo, String txnNo,
                                 long batchId, long amountCents) {
    }

    /**
     * 调整明细行。
     */
    public record AdjustDetailRow(Long id, long adjustmentId, String txnNo, String lineCode,
                                  long amountCents) {
    }

    /**
     * 线路金额汇总行。
     */
    public record LineAmountRow(String lineCode, long amountCents) {
    }
}
