package com.example.starter.payment.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 支付交易仓储：入账、幂等查询、批次聚合、挂账调账翻转与对账补记。
 */
@Repository
public class TransactionRepository {

    /** 交易行视图，含入账与对账所需字段。 */
    public record TxnRow(
            Long id,
            String requestId,
            String fingerprint,
            String channelSerialNo,
            String merchantNo,
            BigDecimal amount,
            BigDecimal feeRate,
            BigDecimal fee,
            Instant occurTime,
            String batchDate,
            String status,
            String source) {
    }

    private static final RowMapper<TxnRow> MAPPER = (rs, n) -> new TxnRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("fingerprint"),
            rs.getString("channel_serial_no"),
            rs.getString("merchant_no"),
            rs.getBigDecimal("amount"),
            rs.getBigDecimal("fee_rate"),
            rs.getBigDecimal("fee"),
            rs.getTimestamp("occur_time").toInstant(),
            rs.getString("batch_date"),
            rs.getString("status"),
            rs.getString("source"));

    private static final String COLUMNS = "id, request_id, fingerprint, channel_serial_no, merchant_no, "
            + "amount, fee_rate, fee, occur_time, batch_date, status, source";

    private final JdbcTemplate jdbc;
    private final JdbcClient client;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.client = JdbcClient.create(jdbc);
    }

    public void insert(TxnRow txn) {
        jdbc.update("INSERT INTO payment_transaction (request_id, fingerprint, channel_serial_no, merchant_no, "
                + "amount, fee_rate, fee, occur_time, batch_date, status, source) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                txn.requestId(), txn.fingerprint(), txn.channelSerialNo(), txn.merchantNo(),
                txn.amount(), txn.feeRate(), txn.fee(), Timestamp.from(txn.occurTime()),
                txn.batchDate(), txn.status(), txn.source());
    }

    public Optional<TxnRow> findByRequestId(String requestId) {
        return client.sql("SELECT " + COLUMNS + " FROM payment_transaction WHERE request_id = :rid")
                .param("rid", requestId)
                .query(MAPPER)
                .optional();
    }

    public Optional<TxnRow> findByChannelSerialNo(String serialNo) {
        return client.sql("SELECT " + COLUMNS + " FROM payment_transaction WHERE channel_serial_no = :sno")
                .param("sno", serialNo)
                .query(MAPPER)
                .optional();
    }

    /** 事务内锁定指定流水的挂账交易，供调账使用；非挂账或不存在返回空。 */
    public Optional<TxnRow> lockSuspendedBySerialNo(String serialNo) {
        return client.sql("SELECT " + COLUMNS + " FROM payment_transaction "
                + "WHERE channel_serial_no = :sno AND status = 'SUSPENDED' FOR UPDATE")
                .param("sno", serialNo)
                .query(MAPPER)
                .optional();
    }

    /** 调账提交：挂账交易在同事务内转为目标批次的已入账交易。 */
    public int markPostedToBatch(long txnId, String toBatchDate) {
        return jdbc.update("UPDATE payment_transaction SET status = 'POSTED', batch_date = ? WHERE id = ?",
                toBatchDate, txnId);
    }

    public List<TxnRow> findPostedByBatch(String batchDate) {
        return client.sql("SELECT " + COLUMNS + " FROM payment_transaction "
                + "WHERE batch_date = :bd AND status = 'POSTED' ORDER BY id")
                .param("bd", batchDate)
                .query(MAPPER)
                .list();
    }

    /** 封账前对批次内已入账交易加锁，与入账插入互斥（依赖间隙锁/表级序列化，见服务层说明）。 */
    public List<TxnRow> lockPostedByBatch(String batchDate) {
        return client.sql("SELECT " + COLUMNS + " FROM payment_transaction "
                + "WHERE batch_date = :bd AND status = 'POSTED' FOR UPDATE")
                .param("bd", batchDate)
                .query(MAPPER)
                .list();
    }
}
