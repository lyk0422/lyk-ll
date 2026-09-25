package com.example.starter.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 放行单数据访问。request_id 唯一约束兜底幂等。
 */
@Repository
public class ReleaseOrderRepository {

    /**
     * 放行单记录（对应 release_order 表）。
     *
     * @param requestId   幂等键
     * @param fingerprint 参数指纹
     * @param barcode     成分血条码
     * @param outcome     RELEASED / REJECTED
     * @param detail      拒绝原因
     * @param createdAt   创建时刻（UTC）
     */
    public record ReleaseOrder(String requestId, String fingerprint, String barcode,
                               String outcome, String detail, Instant createdAt) {
    }

    private final JdbcClient jdbc;

    public ReleaseOrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按幂等键查询放行单。
     */
    public Optional<ReleaseOrder> findByRequestId(String requestId) {
        return jdbc.sql("SELECT request_id, fingerprint, barcode, outcome, detail, created_at"
                        + " FROM release_order WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(this::mapRow)
                .optional();
    }

    /**
     * 插入放行单；request_id 冲突时抛出异常由上层按幂等重放处理。
     */
    public void insert(String requestId, String fingerprint, String barcode,
                       Instant windowStart, Instant windowEnd,
                       String outcome, String detail, Instant now) {
        jdbc.sql("INSERT INTO release_order (request_id, fingerprint, barcode, window_start, window_end,"
                        + " outcome, detail, created_at)"
                        + " VALUES (:requestId, :fingerprint, :barcode, :windowStart, :windowEnd,"
                        + " :outcome, :detail, :now)")
                .param("requestId", requestId)
                .param("fingerprint", fingerprint)
                .param("barcode", barcode)
                .param("windowStart", Timestamp.from(windowStart))
                .param("windowEnd", Timestamp.from(windowEnd))
                .param("outcome", outcome)
                .param("detail", detail)
                .param("now", Timestamp.from(now))
                .update();
    }

    private ReleaseOrder mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ReleaseOrder(
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getString("barcode"),
                rs.getString("outcome"),
                rs.getString("detail"),
                rs.getTimestamp("created_at").toInstant());
    }
}
