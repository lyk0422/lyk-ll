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
 * 退款冲正数据访问：退款单、调账单、负向调整明细、调账批次与票卡关闭登记。
 * 原清分表（metro_transaction / clearing_batch / clearing_detail）只读，永不改写。
 */
@Repository
public class RefundRepository {

    private final JdbcTemplate jdbcTemplate;

    public RefundRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static LocalDateTime toDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static final RowMapper<RefundRow> REFUND_MAPPER = (rs, rowNum) -> new RefundRow(
            rs.getLong("id"),
            rs.getString("refund_no"),
            rs.getString("txn_no"),
            rs.getLong("amount_cents"),
            rs.getString("status"),
            rs.getString("confirmer_one"),
            toDateTime(rs, "confirmer_one_time"),
            rs.getString("confirmer_two"),
            toDateTime(rs, "confirmer_two_time"),
            rs.getString("adjustment_no"),
            rs.getInt("version"),
            rs.getString("apply_request_id"),
            rs.getString("fingerprint"),
            toDateTime(rs, "confirmed_at"));

    private static final RowMapper<AdjustmentRow> ADJUSTMENT_MAPPER = (rs, rowNum) -> new AdjustmentRow(
            rs.getLong("id"),
            rs.getString("adjustment_no"),
            rs.getString("refund_no"),
            rs.getString("txn_no"),
            rs.getLong("amount_cents"),
            rs.getObject("batch_id", Long.class),
            rs.getString("status"),
            rs.getInt("version"),
            toDateTime(rs, "included_at"));

    private static final RowMapper<AdjustmentDetailRow> ADJUSTMENT_DETAIL_MAPPER = (rs, rowNum) ->
            new AdjustmentDetailRow(
                    rs.getLong("id"),
                    rs.getString("adjustment_no"),
                    rs.getString("txn_no"),
                    rs.getLong("original_detail_id"),
                    rs.getString("line_code"),
                    rs.getLong("amount_cents"));

    private static final RowMapper<AdjustmentBatchRow> ADJUSTMENT_BATCH_MAPPER = (rs, rowNum) ->
            new AdjustmentBatchRow(
                    rs.getLong("id"),
                    rs.getString("batch_no"),
                    rs.getString("status"),
                    rs.getLong("total_cents"),
                    rs.getInt("adjustment_count"),
                    rs.getInt("version"),
                    rs.getString("seal_request_id"),
                    toDateTime(rs, "sealed_at"));

    private static final String REFUND_COLUMNS =
            "id, refund_no, txn_no, amount_cents, status, confirmer_one, confirmer_one_time,"
            + " confirmer_two, confirmer_two_time, adjustment_no, version, apply_request_id,"
            + " fingerprint, confirmed_at";

    /** 按退款单号查询（不加锁）。 */
    public Optional<RefundRow> findRefund(String refundNo) {
        List<RefundRow> rows = jdbcTemplate.query(
                "SELECT " + REFUND_COLUMNS + " FROM refund_order WHERE refund_no = ?",
                REFUND_MAPPER, refundNo);
        return rows.stream().findFirst();
    }

    /** 按退款单号查询并加行锁。 */
    public Optional<RefundRow> findRefundForUpdate(String refundNo) {
        List<RefundRow> rows = jdbcTemplate.query(
                "SELECT " + REFUND_COLUMNS + " FROM refund_order WHERE refund_no = ? FOR UPDATE",
                REFUND_MAPPER, refundNo);
        return rows.stream().findFirst();
    }

    /** 新建待确认退款单，版本 1；退款单号重复抛出 DuplicateKeyException。 */
    public void insertRefund(RefundRow row) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update(
                "INSERT INTO refund_order (refund_no, txn_no, amount_cents, status, version,"
                + " apply_request_id, fingerprint, created_at, updated_at)"
                + " VALUES (?,?,?,'PENDING',1,?,?,?,?)",
                row.refundNo(), row.txnNo(), row.amountCents(), row.applyRequestId(),
                row.fingerprint(), Timestamp.valueOf(now), Timestamp.valueOf(now));
    }

    /**
     * 双人确认生效：占用退款单，写入两名复核人、调账单号，版本加 1。
     * 仅当状态仍为 PENDING 且版本匹配时更新，返回受影响行数。
     */
    public int confirmRefund(String refundNo, int expectedVersion, String confirmerOne,
                             LocalDateTime confirmerOneTime, String confirmerTwo,
                             LocalDateTime confirmerTwoTime, String adjustmentNo,
                             LocalDateTime confirmedAt) {
        return jdbcTemplate.update(
                "UPDATE refund_order SET status = 'CONFIRMED', confirmer_one = ?,"
                + " confirmer_one_time = ?, confirmer_two = ?, confirmer_two_time = ?,"
                + " adjustment_no = ?, version = version + 1, confirmed_at = ?, updated_at = ?"
                + " WHERE refund_no = ? AND status = 'PENDING' AND version = ?",
                confirmerOne, Timestamp.valueOf(confirmerOneTime), confirmerTwo,
                Timestamp.valueOf(confirmerTwoTime), adjustmentNo,
                Timestamp.valueOf(confirmedAt), Timestamp.valueOf(LocalDateTime.now()),
                refundNo, expectedVersion);
    }

    /** 登记调账单（REGISTERED）。 */
    public void insertAdjustment(AdjustmentRow row) {
        jdbcTemplate.update(
                "INSERT INTO adjustment_order (adjustment_no, refund_no, txn_no, amount_cents,"
                + " status, created_at) VALUES (?,?,?,?,'REGISTERED',?)",
                row.adjustmentNo(), row.refundNo(), row.txnNo(), row.amountCents(),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 按调账单号查询。 */
    public Optional<AdjustmentRow> findAdjustment(String adjustmentNo) {
        List<AdjustmentRow> rows = jdbcTemplate.query(
                "SELECT id, adjustment_no, refund_no, txn_no, amount_cents, batch_id, status,"
                + " version, included_at FROM adjustment_order WHERE adjustment_no = ?",
                ADJUSTMENT_MAPPER, adjustmentNo);
        return rows.stream().findFirst();
    }

    /** 按调账单号查询并加行锁。 */
    public Optional<AdjustmentRow> findAdjustmentForUpdate(String adjustmentNo) {
        List<AdjustmentRow> rows = jdbcTemplate.query(
                "SELECT id, adjustment_no, refund_no, txn_no, amount_cents, batch_id, status,"
                + " version, included_at FROM adjustment_order WHERE adjustment_no = ? FOR UPDATE",
                ADJUSTMENT_MAPPER, adjustmentNo);
        return rows.stream().findFirst();
    }

    /**
     * 纳入开放批次：仅当调账单仍为 REGISTERED 且版本匹配时更新，版本加 1，返回受影响行数。
     */
    public int includeAdjustment(String adjustmentNo, int expectedVersion, long batchId) {
        return jdbcTemplate.update(
                "UPDATE adjustment_order SET status = 'INCLUDED', batch_id = ?, included_at = ?,"
                + " version = version + 1"
                + " WHERE adjustment_no = ? AND status = 'REGISTERED' AND batch_id IS NULL"
                + " AND version = ?",
                batchId, Timestamp.valueOf(LocalDateTime.now()), adjustmentNo, expectedVersion);
    }

    /** 插入负向调整明细（amountCents 恒为负）。 */
    public void insertAdjustmentDetail(AdjustmentDetailRow row) {
        jdbcTemplate.update(
                "INSERT INTO adjustment_detail (adjustment_no, txn_no, original_detail_id,"
                + " line_code, amount_cents, created_at) VALUES (?,?,?,?,?,?)",
                row.adjustmentNo(), row.txnNo(), row.originalDetailId(), row.lineCode(),
                row.amountCents(), Timestamp.valueOf(LocalDateTime.now()));
    }

    /** 查询某行程全部负向调整明细（逐线路累计退款用）。 */
    public List<AdjustmentDetailRow> findAdjustmentDetailsByTxn(String txnNo) {
        return jdbcTemplate.query(
                "SELECT id, adjustment_no, txn_no, original_detail_id, line_code, amount_cents"
                + " FROM adjustment_detail WHERE txn_no = ? ORDER BY id",
                ADJUSTMENT_DETAIL_MAPPER, txnNo);
    }

    /** 查询某调账单全部负向调整明细。 */
    public List<AdjustmentDetailRow> findAdjustmentDetailsByAdjustment(String adjustmentNo) {
        return jdbcTemplate.query(
                "SELECT id, adjustment_no, txn_no, original_detail_id, line_code, amount_cents"
                + " FROM adjustment_detail WHERE adjustment_no = ? ORDER BY id",
                ADJUSTMENT_DETAIL_MAPPER, adjustmentNo);
    }

    /** 创建 OPEN 调账批次；已存在开放批次时唯一约束冲突抛出 DuplicateKeyException。 */
    public long insertAdjustmentBatch(String batchNo) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(
                    "INSERT INTO adjustment_batch (batch_no, open_slot, status, total_cents,"
                    + " adjustment_count, version, created_at) VALUES (?,'OPEN','OPEN',0,0,1,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, batchNo);
            ps.setTimestamp(2, Timestamp.valueOf(LocalDateTime.now()));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    /** 查询唯一开放调账批次（不加锁）。 */
    public Optional<AdjustmentBatchRow> findOpenBatch() {
        List<AdjustmentBatchRow> rows = jdbcTemplate.query(
                "SELECT id, batch_no, status, total_cents, adjustment_count, version,"
                + " seal_request_id, sealed_at FROM adjustment_batch WHERE open_slot = 'OPEN'",
                ADJUSTMENT_BATCH_MAPPER);
        return rows.stream().findFirst();
    }

    /** 查询唯一开放调账批次并加行锁。 */
    public Optional<AdjustmentBatchRow> findOpenBatchForUpdate() {
        List<AdjustmentBatchRow> rows = jdbcTemplate.query(
                "SELECT id, batch_no, status, total_cents, adjustment_count, version,"
                + " seal_request_id, sealed_at FROM adjustment_batch WHERE open_slot = 'OPEN' FOR UPDATE",
                ADJUSTMENT_BATCH_MAPPER);
        return rows.stream().findFirst();
    }

    /** 按批次号查询。 */
    public Optional<AdjustmentBatchRow> findAdjustmentBatch(String batchNo) {
        List<AdjustmentBatchRow> rows = jdbcTemplate.query(
                "SELECT id, batch_no, status, total_cents, adjustment_count, version,"
                + " seal_request_id, sealed_at FROM adjustment_batch WHERE batch_no = ?",
                ADJUSTMENT_BATCH_MAPPER, batchNo);
        return rows.stream().findFirst();
    }

    /** 按批次号查询并加行锁。 */
    public Optional<AdjustmentBatchRow> findAdjustmentBatchForUpdate(String batchNo) {
        List<AdjustmentBatchRow> rows = jdbcTemplate.query(
                "SELECT id, batch_no, status, total_cents, adjustment_count, version,"
                + " seal_request_id, sealed_at FROM adjustment_batch WHERE batch_no = ? FOR UPDATE",
                ADJUSTMENT_BATCH_MAPPER, batchNo);
        return rows.stream().findFirst();
    }

    /** 按主键查询调账批次。 */
    public Optional<AdjustmentBatchRow> findAdjustmentBatchById(long batchId) {
        List<AdjustmentBatchRow> rows = jdbcTemplate.query(
                "SELECT id, batch_no, status, total_cents, adjustment_count, version,"
                + " seal_request_id, sealed_at FROM adjustment_batch WHERE id = ?",
                ADJUSTMENT_BATCH_MAPPER, batchId);
        return rows.stream().findFirst();
    }

    /** 统计批次内已纳入调账单的退款本金合计（分，正数）。 */
    public long sumAdjustmentAmountsInBatch(long batchId) {
        Long sum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_cents),0) FROM adjustment_order WHERE batch_id = ?",
                Long.class, batchId);
        return sum == null ? 0L : sum;
    }

    /** 统计批次内已纳入调账单笔数。 */
    public int countAdjustmentsInBatch(long batchId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM adjustment_order WHERE batch_id = ?",
                Integer.class, batchId);
        return count == null ? 0 : count;
    }

    /**
     * 封存批次：置 SEALED、释放开放占位、写入合计快照与版本加 1。
     * 仅当批次仍为 OPEN 且版本匹配时更新，返回受影响行数。
     */
    public int sealAdjustmentBatch(long batchId, int expectedVersion, long totalCents,
                                   int adjustmentCount, String sealRequestId,
                                   LocalDateTime sealedAt) {
        return jdbcTemplate.update(
                "UPDATE adjustment_batch SET status = 'SEALED', open_slot = NULL, total_cents = ?,"
                + " adjustment_count = ?, seal_request_id = ?, sealed_at = ?, version = version + 1"
                + " WHERE id = ? AND status = 'OPEN' AND version = ?",
                totalCents, adjustmentCount, sealRequestId, Timestamp.valueOf(sealedAt),
                batchId, expectedVersion);
    }

    /** 票卡是否已关闭。 */
    public boolean isCardClosed(String cardId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metro_card_close WHERE card_id = ?", Integer.class, cardId);
        return count != null && count > 0;
    }

    /** 登记票卡关闭；重复关闭抛 DuplicateKeyException。 */
    public void insertCardClose(String cardId, String requestId, LocalDateTime closedAt) {
        jdbcTemplate.update(
                "INSERT INTO metro_card_close (card_id, request_id, closed_at) VALUES (?,?,?)",
                cardId, requestId, Timestamp.valueOf(closedAt));
    }

    /**
     * 退款单行。
     */
    public record RefundRow(long id, String refundNo, String txnNo, long amountCents, String status,
                            String confirmerOne, LocalDateTime confirmerOneTime,
                            String confirmerTwo, LocalDateTime confirmerTwoTime,
                            String adjustmentNo, int version, String applyRequestId,
                            String fingerprint, LocalDateTime confirmedAt) {
    }

    /**
     * 调账单行。
     */
    public record AdjustmentRow(long id, String adjustmentNo, String refundNo, String txnNo,
                                long amountCents, Long batchId, String status, int version,
                                LocalDateTime includedAt) {
    }

    /**
     * 负向调整明细行。
     */
    public record AdjustmentDetailRow(long id, String adjustmentNo, String txnNo,
                                      long originalDetailId, String lineCode, long amountCents) {
    }

    /**
     * 调账批次行。
     */
    public record AdjustmentBatchRow(long id, String batchNo, String status, long totalCents,
                                     int adjustmentCount, int version, String sealRequestId,
                                     LocalDateTime sealedAt) {
    }
}
