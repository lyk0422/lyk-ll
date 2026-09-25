package com.example.starter.payment.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 调账指令仓储；uk_adjust_txn 保证一笔挂账交易只能成功调账一次。
 */
@Repository
public class AdjustmentRepository {

    private final JdbcTemplate jdbc;

    public AdjustmentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入调账留痕。
     *
     * @throws DuplicateKeyException 该交易已调账过
     */
    public void insert(long transactionId, String channelSerialNo, String fromBatchDate, String toBatchDate,
            BigDecimal amount, String operator) {
        jdbc.update("INSERT INTO adjustment_order (transaction_id, channel_serial_no, from_batch_date, "
                + "to_batch_date, amount, operator, created_at) VALUES (?,?,?,?,?,?,?)",
                transactionId, channelSerialNo, fromBatchDate, toBatchDate, amount,
                operator, Timestamp.from(Instant.now()));
    }
}
