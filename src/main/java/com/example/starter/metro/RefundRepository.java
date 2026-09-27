package com.example.starter.metro;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 封账后冲正（退款）域数据访问：退款单、复核登记、逐线路可退余额、
 * 独立负向调整单与调账批次。所有占用与状态变更均带条件更新，
 * 并发下由行锁与条件 UPDATE 共同保证不超退、不重复计额。
 */
@Repository
public class RefundRepository {

    private final JdbcTemplate jdbcTemplate;

    public RefundRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<CardRow> CARD_MAPPER = (rs, rowNum) -> new CardRow(
            rs.getString("card_id"), rs.getString("status"), rs.getInt("version"));

    private static final RowMapper<RefundOrderRow> REFUND_MAPPER = (rs, rowNum) -> new RefundOrderRow(
            rs.getLong("id"),
            rs.getString("refund_no"),
            rs.getString("txn_no"),
            rs.getLong("amount_cents"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getString("first_reviewer"),
            toDateTime(rs, "first_review_time"),
            rs.getString("final_reviewer"),
            toDateTime(rs, "confirmed_time"),
            rs.getString("apply_request_id"),
            rs.getTimestamp("apply_time").toLocalDateTime());

    private static final RowMapper<ReviewRow> REVIEW_MAPPER = (rs, rowNum) -> new ReviewRow(
            rs.getString("refund_no"), rs.getString("reviewer"),
            rs.getTimestamp("review_time").toLocalDateTime(), rs.getString("request_id"));

    private static final RowMapper<LineShareRow> SHARE_MAPPER = (rs, rowNum) -> new LineShareRow(
            rs.getLong("id"), rs.getString("txn_no"), rs.getString("line_code"),
            rs.getLong("original_share_cents"), rs.getLong("refunded_cents"));

    private static final RowMapper<AdjustmentRow> ADJUSTMENT_MAPPER = (rs, rowNum) -> new AdjustmentRow(
            rs.getLong("id"), rs.getString("adjustment_no"), rs.getString("refund_no"),
            rs.getString("txn_no"), rs.getLong("amount_cents"), toLongObject(rs, "batch_id"),
            rs.getString("confirm_request_id"));

    private static final RowMapper<AdjustmentDetailRow> ADJUSTMENT_DETAIL_MAPPER =
            (rs, rowNum) -> new AdjustmentDetailRow(
                    rs.getLong("id"), rs.getLong("adjustment_id"), rs.getString("line_code"),
                    rs.getLong("amount_cents"));

    private static final RowMapper<AdjustmentBatchRow> BATCH_MAPPER = (rs, rowNum) ->
            new AdjustmentBatchRow(
                    rs.getLong("id"), rs.getString("batch_no"), rs.getString("status"),
                    rs.getLong("total_cents"), rs.getInt("adjustment_count"), rs.getInt("version"),
                    rs.getString("seal_request_id"), toDateTime(rs, "sealed_at"));

    private static final RowMapper<BatchDetailRow> BATCH_DETAIL_MAPPER = (rs, rowNum) ->
            new BatchDetailRow(rs.getLong("id"), rs.getLong("batch_id"), rs.getLong("adjustment_id"),
                    rs.getString("adjustment_no"), rs.getString("txn_no"), rs.getString("line_code"),
                    rs.getLong("amount_cents"));

    private static LocalDateTime toDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static Long toLongObject(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    // ---- 票卡 ----

    /** 查询票卡，不存在返回空。 */
    public Optional<CardRow> findCard(String cardId) {
        return jdbcTemplate.query("SELECT card_id, status, version FROM metro_card WHERE card_id = ?",
                CARD_MAPPER, cardId).stream().findFirst();
    }

    /** 查询票卡并加行锁。 */
    public Optional<CardRow> findCardForUpdate(String cardId) {
        return jdbcTemplate.query("SELECT card_id, status, version FROM metro_card WHERE card_id = ? FOR UPDATE",
                CARD_MAPPER, cardId).stream().findFirst();
    }

    /** 惰性登记票卡主数据（版本 1，ACTIVE）；并发下已存在则忽略。 */
    public void insertCardIfAbsent(String cardId, LocalDateTime now) {
        try {
            jdbcTemplate.update("INSERT INTO metro_card (card_id, status, version, created_at, updated_at)"
                    + " SELECT ?, 'ACTIVE', 1, ?, ? WHERE NOT EXISTS (SELECT 1 FROM metro_card WHERE card_id = ?)",
                    cardId, Timestamp.valueOf(now), Timestamp.valueOf(now), cardId);
        } catch (DuplicateKeyException ex) {
            // 并发惰性登记：以已有票卡为准
        }
    }

    /**
     * 条件关闭票卡：仅 ACTIVE 且版本匹配时生效。
     *
     * @return 是否更新成功（版本不符或已关闭返回 false）
     */
    public boolean closeCard(String cardId, int expectedVersion, LocalDateTime now) {
        int rows = jdbcTemplate.update(
                "UPDATE metro_card SET status = 'CLOSED', version = version + 1, updated_at = ?"
                        + " WHERE card_id = ? AND status = 'ACTIVE' AND version = ?",
                Timestamp.valueOf(now), cardId, expectedVersion);
        return rows == 1;
    }

    // ---- 退款单 ----

    /** 创建退款单（状态 PENDING，版本 1）。 */
    public void insertRefundOrder(RefundOrderRow row) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO refund_order (refund_no, txn_no, amount_cents, status, version,"
                + " apply_request_id, apply_time, created_at, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?)",
                row.refundNo(), row.txnNo(), row.amountCents(), "PENDING", 1,
                row.applyRequestId(), Timestamp.valueOf(row.applyTime()),
                Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    /** 按退款单号查询。 */
    public Optional<RefundOrderRow> findRefundByNo(String refundNo) {
        return jdbcTemplate.query(refundColumns("") + " FROM refund_order WHERE refund_no = ?",
                REFUND_MAPPER, refundNo).stream().findFirst();
    }

    /** 按退款单号查询并加行锁。 */
    public Optional<RefundOrderRow> findRefundByNoForUpdate(String refundNo) {
        return jdbcTemplate.query(refundColumns("") + " FROM refund_order WHERE refund_no = ? FOR UPDATE",
                REFUND_MAPPER, refundNo).stream().findFirst();
    }

    /**
     * 首人复核：仅 PENDING/版本1 时生效，置 IN_REVIEW/版本2。
     *
     * @return 是否更新成功
     */
    public boolean markFirstReview(String refundNo, String reviewer, LocalDateTime reviewTime) {
        int rows = jdbcTemplate.update(
                "UPDATE refund_order SET status = 'IN_REVIEW', version = 2, first_reviewer = ?,"
                        + " first_review_time = ?, updated_at = ?"
                        + " WHERE refund_no = ? AND status = 'PENDING' AND version = 1",
                reviewer, Timestamp.valueOf(reviewTime), Timestamp.valueOf(LocalDateTime.now()), refundNo);
        return rows == 1;
    }

    /**
     * 最终确认：仅 IN_REVIEW/版本2 时生效，置 CONFIRMED/版本3 并记录最终复核人。
     *
     * @return 是否更新成功
     */
    public boolean markConfirmed(String refundNo, String finalReviewer, LocalDateTime confirmedTime) {
        int rows = jdbcTemplate.update(
                "UPDATE refund_order SET status = 'CONFIRMED', version = 3, final_reviewer = ?,"
                        + " confirmed_time = ?, updated_at = ?"
                        + " WHERE refund_no = ? AND status = 'IN_REVIEW' AND version = 2",
                finalReviewer, Timestamp.valueOf(confirmedTime), Timestamp.valueOf(LocalDateTime.now()),
                refundNo);
        return rows == 1;
    }

    /** 插入复核登记；同退款单同复核人由唯一约束拒绝。 */
    public void insertReview(String refundNo, String reviewer, LocalDateTime reviewTime, String requestId) {
        jdbcTemplate.update(
                "INSERT INTO refund_review (refund_no, reviewer, review_time, request_id, created_at)"
                        + " VALUES (?,?,?,?,?)",
                refundNo, reviewer, Timestamp.valueOf(reviewTime), requestId,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 查询退款单全部复核登记并加行锁，按登记顺序返回。 */
    public List<ReviewRow> findReviewsForUpdate(String refundNo) {
        return jdbcTemplate.query(
                "SELECT refund_no, reviewer, review_time, request_id FROM refund_review"
                        + " WHERE refund_no = ? ORDER BY id FOR UPDATE",
                REVIEW_MAPPER, refundNo);
    }

    private static String refundColumns(String alias) {
        String prefix = alias == null || alias.isEmpty() ? "" : alias + ".";
        return "SELECT " + prefix + "id, " + prefix + "refund_no, " + prefix + "txn_no, "
                + prefix + "amount_cents, " + prefix + "status, " + prefix + "version, "
                + prefix + "first_reviewer, " + prefix + "first_review_time, "
                + prefix + "final_reviewer, " + prefix + "confirmed_time, "
                + prefix + "apply_request_id, " + prefix + "apply_time";
    }

    // ---- 逐线路可退余额 ----

    /** 初始化行程某线路原实收份额快照（退款单首次创建时逐线路写入）。 */
    public void insertLineShare(String txnNo, String lineCode, long originalShareCents) {
        jdbcTemplate.update(
                "INSERT INTO refund_line_share (txn_no, line_code, original_share_cents,"
                + " refunded_cents, created_at, updated_at) VALUES (?,?,?,0,?,?)",
                txnNo, lineCode, originalShareCents,
                Timestamp.valueOf(LocalDateTime.now()), Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 查询行程全部线路余额并加行锁（最终确认分摊占用时使用），按线路编号升序。 */
    public List<LineShareRow> findSharesByTxnForUpdate(String txnNo) {
        return jdbcTemplate.query(
                "SELECT id, txn_no, line_code, original_share_cents, refunded_cents"
                        + " FROM refund_line_share WHERE txn_no = ? ORDER BY line_code FOR UPDATE",
                SHARE_MAPPER, txnNo);
    }

    /** 查询行程全部线路余额（只读查询用），按线路编号升序。 */
    public List<LineShareRow> findSharesByTxn(String txnNo) {
        return jdbcTemplate.query(
                "SELECT id, txn_no, line_code, original_share_cents, refunded_cents"
                        + " FROM refund_line_share WHERE txn_no = ? ORDER BY line_code",
                SHARE_MAPPER, txnNo);
    }

    /**
     * 条件占用某线路可退余额：占用后不得超过原份额。
     *
     * @return 1 表示占用成功；0 表示余额不足（并发超退被拒绝）
     */
    public int occupyLineShare(String txnNo, String lineCode, long refundCents) {
        return jdbcTemplate.update(
                "UPDATE refund_line_share SET refunded_cents = refunded_cents + ?, updated_at = ?"
                        + " WHERE txn_no = ? AND line_code = ? AND refunded_cents + ? <= original_share_cents",
                refundCents, Timestamp.valueOf(LocalDateTime.now()), txnNo, lineCode, refundCents);
    }

    // ---- 调整单与负向明细 ----

    /** 创建调整单，返回主键。 */
    public long insertAdjustment(String adjustmentNo, String refundNo, String txnNo, long amountCents,
                                 String confirmRequestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO refund_adjustment (adjustment_no, refund_no, txn_no, amount_cents,"
                            + " batch_id, confirm_request_id, created_at) VALUES (?,?,?,?,NULL,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, adjustmentNo);
            ps.setString(2, refundNo);
            ps.setString(3, txnNo);
            ps.setLong(4, amountCents);
            ps.setString(5, confirmRequestId);
            ps.setTimestamp(6, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 插入调整单负向明细（amountCents 为负数）。 */
    public void insertAdjustmentDetail(long adjustmentId, String lineCode, long signedAmountCents) {
        jdbcTemplate.update(
                "INSERT INTO refund_adjustment_detail (adjustment_id, line_code, amount_cents, created_at)"
                        + " VALUES (?,?,?,?)",
                adjustmentId, lineCode, signedAmountCents, Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 按调整单号查询。 */
    public Optional<AdjustmentRow> findAdjustmentByNo(String adjustmentNo) {
        return jdbcTemplate.query(
                "SELECT id, adjustment_no, refund_no, txn_no, amount_cents, batch_id, confirm_request_id"
                        + " FROM refund_adjustment WHERE adjustment_no = ?",
                ADJUSTMENT_MAPPER, adjustmentNo).stream().findFirst();
    }

    /** 按调整单号查询并加行锁。 */
    public Optional<AdjustmentRow> findAdjustmentByNoForUpdate(String adjustmentNo) {
        return jdbcTemplate.query(
                "SELECT id, adjustment_no, refund_no, txn_no, amount_cents, batch_id, confirm_request_id"
                        + " FROM refund_adjustment WHERE adjustment_no = ? FOR UPDATE",
                ADJUSTMENT_MAPPER, adjustmentNo).stream().findFirst();
    }

    /** 按退款单号查询调整单。 */
    public Optional<AdjustmentRow> findAdjustmentByRefund(String refundNo) {
        return jdbcTemplate.query(
                "SELECT id, adjustment_no, refund_no, txn_no, amount_cents, batch_id, confirm_request_id"
                        + " FROM refund_adjustment WHERE refund_no = ?",
                ADJUSTMENT_MAPPER, refundNo).stream().findFirst();
    }

    /** 查询调整单全部负向明细，按线路编号升序。 */
    public List<AdjustmentDetailRow> findAdjustmentDetails(long adjustmentId) {
        return jdbcTemplate.query(
                "SELECT id, adjustment_id, line_code, amount_cents FROM refund_adjustment_detail"
                        + " WHERE adjustment_id = ? ORDER BY line_code",
                ADJUSTMENT_DETAIL_MAPPER, adjustmentId);
    }

    /**
     * 将调整单纳入批次：仅尚未纳入（batch_id IS NULL）时生效。
     *
     * @return 1 首次纳入；0 已纳入其他批次（重复纳入不重复计额）
     */
    public int assignAdjustmentBatch(long adjustmentId, long batchId) {
        return jdbcTemplate.update(
                "UPDATE refund_adjustment SET batch_id = ? WHERE id = ? AND batch_id IS NULL",
                batchId, adjustmentId);
    }

    // ---- 调账批次 ----

    /** 创建开放调账批次（状态 OPEN，版本 1），返回主键。 */
    public long insertAdjustmentBatch(String batchNo, String openRequestId, LocalDateTime now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO adjustment_batch (batch_no, status, total_cents, adjustment_count,"
                            + " version, open_request_id, created_at) VALUES (?,'OPEN',0,0,1,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setString(2, openRequestId);
            ps.setTimestamp(3, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 查询调账批次。 */
    public Optional<AdjustmentBatchRow> findAdjustmentBatchByNo(String batchNo) {
        return jdbcTemplate.query(batchColumns() + " FROM adjustment_batch WHERE batch_no = ?",
                BATCH_MAPPER, batchNo).stream().findFirst();
    }

    /** 查询调账批次并加行锁。 */
    public Optional<AdjustmentBatchRow> findAdjustmentBatchByNoForUpdate(String batchNo) {
        return jdbcTemplate.query(batchColumns() + " FROM adjustment_batch WHERE batch_no = ? FOR UPDATE",
                BATCH_MAPPER, batchNo).stream().findFirst();
    }

    /** 按主键查询调账批次。 */
    public Optional<AdjustmentBatchRow> findAdjustmentBatchById(long batchId) {
        return jdbcTemplate.query(batchColumns() + " FROM adjustment_batch WHERE id = ?",
                BATCH_MAPPER, batchId).stream().findFirst();
    }

    /** 纳入成功后批次调整单数与版本加 1（条件版本防并发覆盖）。 */
    public boolean incrementBatchAfterInclude(long batchId, int expectedVersion) {
        int rows = jdbcTemplate.update(
                "UPDATE adjustment_batch SET adjustment_count = adjustment_count + 1, version = version + 1"
                        + " WHERE id = ? AND version = ? AND status = 'OPEN'",
                batchId, expectedVersion);
        return rows == 1;
    }

    /** 插入批次负向明细。 */
    public void insertBatchDetail(long batchId, long adjustmentId, String txnNo, String lineCode,
                                  long signedAmountCents) {
        jdbcTemplate.update(
                "INSERT INTO adjustment_detail (batch_id, adjustment_id, txn_no, line_code,"
                        + " amount_cents, created_at) VALUES (?,?,?,?,?,?)",
                batchId, adjustmentId, txnNo, lineCode, signedAmountCents,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 查询批次全部负向明细，按录入顺序。 */
    public List<BatchDetailRow> findBatchDetails(long batchId) {
        return jdbcTemplate.query(
                "SELECT d.id, d.batch_id, d.adjustment_id, a.adjustment_no, d.txn_no, d.line_code,"
                        + " d.amount_cents FROM adjustment_detail d"
                        + " JOIN refund_adjustment a ON a.id = d.adjustment_id"
                        + " WHERE d.batch_id = ? ORDER BY d.id",
                BATCH_DETAIL_MAPPER, batchId);
    }

    /** 统计批次内调整单数量。 */
    public int countAdjustmentsInBatch(long batchId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT adjustment_id) FROM adjustment_detail WHERE batch_id = ?",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }

    /**
     * 封存批次：仅 OPEN 且版本匹配时生效，写入合计快照，版本加 1。
     *
     * @return 是否封存成功
     */
    public boolean sealAdjustmentBatch(long batchId, long totalCents, int adjustmentCount,
                                       String sealRequestId, LocalDateTime sealTime, int expectedVersion) {
        int rows = jdbcTemplate.update(
                "UPDATE adjustment_batch SET status = 'SEALED', total_cents = ?, adjustment_count = ?,"
                        + " version = version + 1, seal_request_id = ?, sealed_at = ?"
                        + " WHERE id = ? AND status = 'OPEN' AND version = ?",
                totalCents, adjustmentCount, sealRequestId, Timestamp.valueOf(sealTime),
                batchId, expectedVersion);
        return rows == 1;
    }

    private static String batchColumns() {
        return "SELECT id, batch_no, status, total_cents, adjustment_count, version, seal_request_id, sealed_at";
    }

    /**
     * 票卡行。
     */
    public record CardRow(String cardId, String status, int version) {
    }

    /**
     * 退款单行。
     */
    public record RefundOrderRow(long id, String refundNo, String txnNo, long amountCents,
                                 String status, int version, String firstReviewer,
                                 LocalDateTime firstReviewTime, String finalReviewer,
                                 LocalDateTime confirmedTime, String applyRequestId,
                                 LocalDateTime applyTime) {
    }

    /**
     * 退款复核登记行。
     */
    public record ReviewRow(String refundNo, String reviewer, LocalDateTime reviewTime, String requestId) {
    }

    /**
     * 行程逐线路可退余额行。
     */
    public record LineShareRow(long id, String txnNo, String lineCode, long originalShareCents,
                               long refundedCents) {
    }

    /**
     * 退款调整单行。
     */
    public record AdjustmentRow(long id, String adjustmentNo, String refundNo, String txnNo,
                                long amountCents, Long batchId, String confirmRequestId) {
    }

    /**
     * 调整单负向明细行。
     */
    public record AdjustmentDetailRow(long id, long adjustmentId, String lineCode, long amountCents) {
    }

    /**
     * 调账批次行。
     */
    public record AdjustmentBatchRow(long id, String batchNo, String status, long totalCents,
                                     int adjustmentCount, int version, String sealRequestId,
                                     LocalDateTime sealedAt) {
    }

    /**
     * 调账批次负向明细行。
     */
    public record BatchDetailRow(long id, long batchId, long adjustmentId, String adjustmentNo,
                                 String txnNo, String lineCode, long amountCents) {
    }
}
