package com.example.starter.repository;

import com.example.starter.entity.PaymentTxn;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 支付交易流水表数据访问。 */
@Repository
public class TxnRepository {

    private static final RowMapper<PaymentTxn> MAPPER = new TxnRowMapper();

    private final JdbcTemplate jdbcTemplate;

    public TxnRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PaymentTxn findByRequestId(String requestId) {
        List<PaymentTxn> list = jdbcTemplate.query(
                "SELECT id, request_id, fingerprint, channel_flow_no, merchant_no, amount, fee, batch_date, "
                        + "origin_batch_date, status, occurred_at, created_at, moved_at "
                        + "FROM payment_txn WHERE request_id = ?",
                MAPPER, requestId);
        return list.isEmpty() ? null : list.get(0);
    }

    public PaymentTxn findById(long id) {
        List<PaymentTxn> list = jdbcTemplate.query(
                "SELECT id, request_id, fingerprint, channel_flow_no, merchant_no, amount, fee, batch_date, "
                        + "origin_batch_date, status, occurred_at, created_at, moved_at "
                        + "FROM payment_txn WHERE id = ?",
                MAPPER, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public PaymentTxn findByBatchAndFlow(LocalDate batchDate, String channelFlowNo) {
        List<PaymentTxn> list = jdbcTemplate.query(
                "SELECT id, request_id, fingerprint, channel_flow_no, merchant_no, amount, fee, batch_date, "
                        + "origin_batch_date, status, occurred_at, created_at, moved_at "
                        + "FROM payment_txn WHERE batch_date = ? AND channel_flow_no = ?",
                MAPPER, batchDate, channelFlowNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 按原批次与流水号查找已调账交易（调账后 batch_date 已变更为目标批次）。 */
    public PaymentTxn findMovedByOriginAndFlow(LocalDate originBatchDate, String channelFlowNo) {
        List<PaymentTxn> list = jdbcTemplate.query(
                "SELECT id, request_id, fingerprint, channel_flow_no, merchant_no, amount, fee, batch_date, "
                        + "origin_batch_date, status, occurred_at, created_at, moved_at "
                        + "FROM payment_txn WHERE origin_batch_date = ? AND channel_flow_no = ? AND status = 'MOVED'",
                MAPPER, originBatchDate, channelFlowNo);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 行级锁查找挂账交易（必须在事务内）。 */
    public PaymentTxn findSuspendedForUpdate(LocalDate fromBatchDate, String channelFlowNo) {
        List<PaymentTxn> list = jdbcTemplate.query(
                "SELECT id, request_id, fingerprint, channel_flow_no, merchant_no, amount, fee, batch_date, "
                        + "origin_batch_date, status, occurred_at, created_at, moved_at "
                        + "FROM payment_txn WHERE batch_date = ? AND channel_flow_no = ? AND status = 'SUSPENDED' FOR UPDATE",
                MAPPER, fromBatchDate, channelFlowNo);
        return list.isEmpty() ? null : list.get(0);
    }

    public void insert(PaymentTxn txn) {
        jdbcTemplate.update(
                "INSERT INTO payment_txn (request_id, fingerprint, channel_flow_no, merchant_no, amount, fee, "
                        + "batch_date, origin_batch_date, status, occurred_at, created_at, moved_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                txn.getRequestId(), txn.getFingerprint(), txn.getChannelFlowNo(), txn.getMerchantNo(),
                txn.getAmount(), txn.getFee(), txn.getBatchDate(), txn.getOriginBatchDate(), txn.getStatus(),
                Timestamp.from(txn.getOccurredAt()), Timestamp.from(txn.getCreatedAt()),
                txn.getMovedAt() == null ? null : Timestamp.from(txn.getMovedAt()));
    }

    /** 挂账调账：仅当交易仍为 SUSPENDED 时可改挂新批，返回受影响行数。 */
    public int markMoved(long txnId, LocalDate toBatchDate, Instant movedAt) {
        return jdbcTemplate.update(
                "UPDATE payment_txn SET origin_batch_date = batch_date, batch_date = ?, status = 'MOVED', moved_at = ? "
                        + "WHERE id = ? AND status = 'SUSPENDED'",
                toBatchDate, Timestamp.from(movedAt), txnId);
    }

    /** 批次内有效交易：直接入账(POSTED)与挂账调账转入(MOVED)均计入该批次清算与对账。 */
    public List<PaymentTxn> findEffectiveTxnsByBatch(LocalDate batchDate) {
        return jdbcTemplate.query(
                "SELECT id, request_id, fingerprint, channel_flow_no, merchant_no, amount, fee, batch_date, "
                        + "origin_batch_date, status, occurred_at, created_at, moved_at "
                        + "FROM payment_txn WHERE batch_date = ? AND status IN ('POSTED', 'MOVED')",
                MAPPER, batchDate);
    }

    private static class TxnRowMapper implements RowMapper<PaymentTxn> {
        @Override
        public PaymentTxn mapRow(ResultSet rs, int rowNum) throws SQLException {
            PaymentTxn t = new PaymentTxn();
            t.setId(rs.getLong("id"));
            t.setRequestId(rs.getString("request_id"));
            t.setFingerprint(rs.getString("fingerprint"));
            t.setChannelFlowNo(rs.getString("channel_flow_no"));
            t.setMerchantNo(rs.getString("merchant_no"));
            t.setAmount(rs.getLong("amount"));
            t.setFee(rs.getLong("fee"));
            t.setBatchDate(rs.getDate("batch_date").toLocalDate());
            java.sql.Date origin = rs.getDate("origin_batch_date");
            t.setOriginBatchDate(origin == null ? null : origin.toLocalDate());
            t.setStatus(rs.getString("status"));
            t.setOccurredAt(rs.getTimestamp("occurred_at").toInstant());
            t.setCreatedAt(rs.getTimestamp("created_at").toInstant());
            Timestamp movedAt = rs.getTimestamp("moved_at");
            t.setMovedAt(movedAt == null ? null : movedAt.toInstant());
            return t;
        }
    }
}
