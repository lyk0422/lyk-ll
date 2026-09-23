package com.example.starter.ros.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 事务幂等记录数据访问：保存已完成事务的原始结果。 */
@Repository
public class TransactionJdbcRepository {

    private final JdbcTemplate jdbc;

    public TransactionJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 已完成事务是否存在。 */
    public boolean exists(String transactionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ros_transaction WHERE transaction_id = ?",
                Integer.class, transactionId);
        return count != null && count > 0;
    }

    /** 读取已完成事务的请求指纹；不存在返回 null。 */
    public String findFingerprint(String transactionId) {
        List<String> values = jdbc.query(
                "SELECT request_fingerprint FROM ros_transaction WHERE transaction_id = ?",
                (rs, n) -> rs.getString("request_fingerprint"), transactionId);
        return values.isEmpty() ? null : values.get(0);
    }

    /** 读取已完成事务的响应 JSON；不存在返回 null。 */
    public String findResponseJson(String transactionId) {
        List<String> values = jdbc.query(
                "SELECT response_json FROM ros_transaction WHERE transaction_id = ?",
                (rs, n) -> rs.getString("response_json"), transactionId);
        return values.isEmpty() ? null : values.get(0);
    }

    /**
     * 插入完成记录；事务 ID 冲突时返回 false（并发重复事务）。
     */
    public boolean insertIfAbsent(String transactionId, boolean committed,
                                  String requestFingerprint, String responseJson, long createdAtMs) {
        try {
            jdbc.update(
                    "INSERT INTO ros_transaction"
                            + " (transaction_id, committed, request_fingerprint, response_json, created_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    transactionId, committed, requestFingerprint, responseJson, createdAtMs);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
}
