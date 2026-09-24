package com.example.starter.blood.repo;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 发血记录数据访问：与库存扣减在同一事务写入，同生同灭。
 */
@Repository
public class IssueRecordRepository {

    private final JdbcClient jdbc;

    public IssueRecordRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 写入发血记录。
     *
     * @param requestId   来源放行请求幂等键
     * @param barcode     发出成分血条码
     * @param windowStart 放行时间窗起点（UTC）
     * @param windowEnd   放行时间窗终点（UTC）
     * @param issuedAt    实际放行时刻（UTC）
     */
    public void insert(String requestId, String barcode, Instant windowStart, Instant windowEnd, Instant issuedAt) {
        jdbc.sql("INSERT INTO issue_record (request_id, barcode, window_start, window_end, issued_at)"
                        + " VALUES (:requestId, :barcode, :windowStart, :windowEnd, :issuedAt)")
                .param("requestId", requestId)
                .param("barcode", barcode)
                .param("windowStart", Timestamp.from(windowStart))
                .param("windowEnd", Timestamp.from(windowEnd))
                .param("issuedAt", Timestamp.from(issuedAt))
                .update();
    }

    /**
     * 统计指定条码的发血记录数（测试与核对用）。
     *
     * @param barcode 条码
     * @return 记录数
     */
    public long countByBarcode(String barcode) {
        return jdbc.sql("SELECT COUNT(*) FROM issue_record WHERE barcode = :barcode")
                .param("barcode", barcode)
                .query(Long.class)
                .single();
    }
}
