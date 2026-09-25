package com.example.starter.metro.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 交易流水数据访问：流水号全局唯一，重复插入由唯一约束拒绝。
 */
@Repository
public class TransactionRepository {

    private final JdbcTemplate jdbcTemplate;

    public TransactionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入交易流水；tx_no 重复时抛出 DuplicateKeyException，由服务层转换为 409。
     */
    public void insert(String txNo, String requestId, String ticketId, String txType,
            String station, LocalDateTime occurredAt, LocalDateTime now) {
        jdbcTemplate.update(
                "INSERT INTO metro_transaction(tx_no, request_id, ticket_id, tx_type, station,"
                        + " occurred_at, created_at) VALUES (?,?,?,?,?,?,?)",
                txNo, requestId, ticketId, txType, station,
                Timestamp.valueOf(occurredAt), Timestamp.valueOf(now));
    }

    public boolean exists(String txNo) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM metro_transaction WHERE tx_no = ?", Integer.class, txNo);
        return count != null && count > 0;
    }
}
