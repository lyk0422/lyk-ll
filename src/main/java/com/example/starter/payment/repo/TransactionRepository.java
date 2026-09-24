package com.example.starter.payment.repo;

import com.example.starter.payment.PayTransaction;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 支付交易数据访问。
 */
@Repository
public class TransactionRepository {

    private static final RowMapper<PayTransaction> MAPPER = (rs, i) -> {
        Long batchId = rs.getLong("batch_id");
        if (rs.wasNull()) {
            batchId = null;
        }
        return new PayTransaction(rs.getLong("id"), rs.getString("request_id"), rs.getString("fingerprint"),
                rs.getString("serial_no"), rs.getString("merchant_no"), rs.getLong("amount"),
                rs.getLong("fee"), rs.getTimestamp("txn_time").toInstant(), batchId, rs.getString("status"));
    };

    private final JdbcTemplate jdbc;

    public TransactionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PayTransaction insert(String requestId, String fingerprint, String serialNo, String merchantNo,
                                 long amount, long fee, Instant txnTime, Long batchId, String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO pay_transaction (request_id, fingerprint, serial_no, merchant_no, amount, fee,"
                            + " txn_time, batch_id, status) VALUES (?,?,?,?,?,?,?,?,?)",
                    new String[]{"id"});
            ps.setString(1, requestId);
            ps.setString(2, fingerprint);
            ps.setString(3, serialNo);
            ps.setString(4, merchantNo);
            ps.setLong(5, amount);
            ps.setLong(6, fee);
            ps.setTimestamp(7, Timestamp.from(txnTime));
            if (batchId == null) {
                ps.setNull(8, java.sql.Types.BIGINT);
            } else {
                ps.setLong(8, batchId);
            }
            ps.setString(9, status);
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return new PayTransaction(id, requestId, fingerprint, serialNo, merchantNo, amount, fee, txnTime,
                batchId, status);
    }

    public Optional<PayTransaction> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM pay_transaction WHERE request_id = ?", MAPPER, requestId)
                .stream().findFirst();
    }

    public Optional<PayTransaction> findBySerialNo(String serialNo) {
        return jdbc.query("SELECT * FROM pay_transaction WHERE serial_no = ?", MAPPER, serialNo)
                .stream().findFirst();
    }

    /** 按流水号加行锁读取（调账用），须在事务内调用。 */
    public Optional<PayTransaction> lockBySerialNo(String serialNo) {
        return jdbc.query("SELECT * FROM pay_transaction WHERE serial_no = ? FOR UPDATE", MAPPER, serialNo)
                .stream().findFirst();
    }

    /** 调账：将挂账交易转入目标批次并置为已入账。 */
    public void moveToBatch(long txnId, long batchId) {
        jdbc.update("UPDATE pay_transaction SET batch_id = ?, status = ? WHERE id = ?",
                batchId, PayTransaction.POSTED, txnId);
    }

    /** 查询批次内已入账交易。 */
    public List<PayTransaction> findPostedByBatchId(long batchId) {
        return jdbc.query("SELECT * FROM pay_transaction WHERE batch_id = ? AND status = ?",
                MAPPER, batchId, PayTransaction.POSTED);
    }

    /** 按批次汇总各商户清算金额。 */
    public List<MerchantAggregate> aggregateByBatch(long batchId) {
        return jdbc.query("SELECT merchant_no, COUNT(*) AS txn_count, SUM(amount) AS total_amount,"
                        + " SUM(fee) AS total_fee FROM pay_transaction WHERE batch_id = ? AND status = ?"
                        + " GROUP BY merchant_no ORDER BY merchant_no",
                (rs, i) -> new MerchantAggregate(rs.getString("merchant_no"), rs.getInt("txn_count"),
                        rs.getLong("total_amount"), rs.getLong("total_fee")),
                batchId, PayTransaction.POSTED);
    }

    /** 商户清算汇总行。 */
    public record MerchantAggregate(String merchantNo, int txnCount, long totalAmount, long totalFee) {
    }
}
