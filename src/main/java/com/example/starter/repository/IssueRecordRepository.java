package com.example.starter.repository;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 发血记录数据访问。与放行扣减在同一事务内写入，同生同灭。
 */
@Repository
public class IssueRecordRepository {

    private final JdbcClient jdbc;

    public IssueRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入发血记录。
     */
    public void insert(String requestId, String barcode, Instant issuedAt) {
        jdbc.sql("INSERT INTO issue_record (request_id, barcode, issued_at)"
                        + " VALUES (:requestId, :barcode, :issuedAt)")
                .param("requestId", requestId)
                .param("barcode", barcode)
                .param("issuedAt", Timestamp.from(issuedAt))
                .update();
    }

    /**
     * 统计指定条码的发血记录数，用于测试断言原子性。
     */
    public long countByBarcode(String barcode) {
        return jdbc.sql("SELECT COUNT(*) FROM issue_record WHERE barcode = :barcode")
                .param("barcode", barcode)
                .query(Long.class)
                .single();
    }
}
