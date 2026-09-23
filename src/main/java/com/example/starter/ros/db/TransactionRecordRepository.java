package com.example.starter.ros.db;

import com.example.starter.ros.TransactionRecord;
import com.example.starter.ros.TransactionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

/**
 * 事务记录数据访问，支撑幂等与同键异参冲突。
 */
@Repository
public class TransactionRecordRepository {

    private static final RowMapper<TransactionRecord> MAPPER = (rs, n) -> new TransactionRecord(
            rs.getString("transaction_id"),
            rs.getString("request_hash"),
            TransactionStatus.valueOf(rs.getString("status")),
            rs.getString("response_json"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbcTemplate;

    public TransactionRecordRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TransactionRecord> find(String transactionId) {
        return jdbcTemplate.query(
                        "SELECT transaction_id, request_hash, status, response_json, created_at "
                                + "FROM transaction_record WHERE transaction_id = ?",
                        MAPPER, transactionId)
                .stream()
                .findFirst();
    }

    public void insert(TransactionRecord record) {
        jdbcTemplate.update(
                "INSERT INTO transaction_record (transaction_id, request_hash, status, response_json, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                record.transactionId(), record.requestHash(), record.status().name(),
                record.responseJson(), Timestamp.from(record.createdAt()));
    }
}
