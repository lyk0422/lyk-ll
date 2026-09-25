package com.example.starter.repository;

import com.example.starter.domain.ReleaseOrder;
import com.example.starter.domain.ReleaseStatus;
import com.example.starter.domain.RejectReason;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 放行单仓储，request_id 唯一约束承载幂等语义。
 */
@Repository
public class ReleaseOrderRepository {

    private static final String COLUMNS = "id, request_id, barcode, window_start, window_end, fingerprint, "
            + "status, reject_reason, issued_at, created_at";

    private final NamedParameterJdbcTemplate jdbc;

    public ReleaseOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ReleaseOrder> MAPPER = (rs, n) -> {
        ReleaseOrder o = new ReleaseOrder();
        o.setId(rs.getLong("id"));
        o.setRequestId(rs.getString("request_id"));
        o.setBarcode(rs.getString("barcode"));
        o.setWindowStart(rs.getTimestamp("window_start").toInstant());
        o.setWindowEnd(rs.getTimestamp("window_end").toInstant());
        o.setFingerprint(rs.getString("fingerprint"));
        o.setStatus(ReleaseStatus.valueOf(rs.getString("status")));
        String reason = rs.getString("reject_reason");
        o.setRejectReason(reason == null ? null : RejectReason.valueOf(reason));
        java.sql.Timestamp issued = rs.getTimestamp("issued_at");
        o.setIssuedAt(issued == null ? null : issued.toInstant());
        o.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return o;
    };

    /**
     * 插入放行单；requestId 冲突由数据库唯一约束拒绝。
     */
    public void insert(ReleaseOrder order) {
        String sql = "INSERT INTO release_order (request_id, barcode, window_start, window_end, fingerprint, "
                + "status, reject_reason, issued_at, created_at) VALUES (:requestId, :barcode, "
                + ":windowStart, :windowEnd, :fingerprint, :status, :rejectReason, :issuedAt, :createdAt)";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("requestId", order.getRequestId())
                .addValue("barcode", order.getBarcode())
                .addValue("windowStart", BloodUnitRepository.ts(order.getWindowStart()))
                .addValue("windowEnd", BloodUnitRepository.ts(order.getWindowEnd()))
                .addValue("fingerprint", order.getFingerprint())
                .addValue("status", order.getStatus().name())
                .addValue("rejectReason", order.getRejectReason() == null ? null : order.getRejectReason().name())
                .addValue("issuedAt", BloodUnitRepository.ts(order.getIssuedAt()))
                .addValue("createdAt", BloodUnitRepository.ts(order.getCreatedAt()));
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(sql, p, keyHolder, new String[]{"id"});
        Number key = keyHolder.getKey();
        if (key != null) {
            order.setId(key.longValue());
        }
    }

    public Optional<ReleaseOrder> findByRequestId(String requestId) {
        String sql = "SELECT " + COLUMNS + " FROM release_order WHERE request_id = :requestId";
        List<ReleaseOrder> list = jdbc.query(sql,
                new MapSqlParameterSource("requestId", requestId), MAPPER);
        return list.stream().findFirst();
    }

    /**
     * 同事务内补齐放行单最终结论与发血时刻。
     */
    public void updateOutcome(ReleaseOrder order) {
        String sql = "UPDATE release_order SET status = :status, reject_reason = :rejectReason, "
                + "issued_at = :issuedAt WHERE id = :id";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", order.getId())
                .addValue("status", order.getStatus().name())
                .addValue("rejectReason", order.getRejectReason() == null ? null : order.getRejectReason().name())
                .addValue("issuedAt", BloodUnitRepository.ts(order.getIssuedAt()));
        jdbc.update(sql, p);
    }
}
