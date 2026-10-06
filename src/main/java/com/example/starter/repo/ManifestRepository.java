package com.example.starter.repo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 收运联单、处理回执、异常核查与日结汇总的数据访问。
 */
@Repository
public class ManifestRepository {

    private final JdbcClient jdbc;

    public ManifestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 联单行。重量单位 kg，时间均为 UTC。
     */
    public record ManifestRow(
            long id,
            String requestId,
            String fingerprint,
            long vehicleId,
            String community,
            String category,
            BigDecimal grossKg,
            BigDecimal tareKg,
            BigDecimal netKg,
            long facilityId,
            String status,
            Instant createdAt) {
    }

    /**
     * 回执行。
     */
    public record ReceiptRow(
            long id,
            String requestId,
            String fingerprint,
            long manifestId,
            BigDecimal netKg,
            BigDecimal deviationPct,
            String result,
            Instant receivedAt) {
    }

    /**
     * 异常核查留痕行。
     */
    public record ReviewRow(
            long id,
            String requestId,
            String fingerprint,
            long manifestId,
            String conclusion,
            String note,
            Instant reviewedAt) {
    }

    /**
     * 日结汇总行：按品类汇总进场（进）与回执（出）数量。
     */
    public record SettlementRow(
            String category,
            long inboundCount,
            BigDecimal inboundNetKg,
            long outboundCount,
            BigDecimal outboundNetKg) {
    }

    /**
     * 插入联单并返回自增 id；request_id 与 (vehicle_id, open_flag) 唯一约束由数据库保证。
     */
    public long insertManifest(String requestId, String fingerprint, long vehicleId, String community,
                               String category, BigDecimal grossKg, BigDecimal tareKg, BigDecimal netKg,
                               long facilityId, String status, Instant createdAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO manifest
                            (request_id, fingerprint, vehicle_id, community, category,
                             gross_kg, tare_kg, net_kg, facility_id, status, open_flag, created_at)
                        VALUES
                            (:requestId, :fingerprint, :vehicleId, :community, :category,
                             :grossKg, :tareKg, :netKg, :facilityId, :status, :openFlag, :createdAt)
                        """)
                .param("requestId", requestId)
                .param("fingerprint", fingerprint)
                .param("vehicleId", vehicleId)
                .param("community", community)
                .param("category", category)
                .param("grossKg", grossKg)
                .param("tareKg", tareKg)
                .param("netKg", netKg)
                .param("facilityId", facilityId)
                .param("status", status)
                .param("openFlag", "IN_TRANSIT".equals(status) ? 1 : null)
                .param("createdAt", Timestamp.from(createdAt))
                .update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("联单插入未返回自增主键");
        }
        return key.longValue();
    }

    public Optional<ManifestRow> findManifestByRequestId(String requestId) {
        return jdbc.sql("""
                        SELECT id, request_id, fingerprint, vehicle_id, community, category,
                               gross_kg, tare_kg, net_kg, facility_id, status, created_at
                        FROM manifest WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(this::mapManifest)
                .optional();
    }

    public Optional<ManifestRow> findManifestById(long id) {
        return jdbc.sql("""
                        SELECT id, request_id, fingerprint, vehicle_id, community, category,
                               gross_kg, tare_kg, net_kg, facility_id, status, created_at
                        FROM manifest WHERE id = :id
                        """)
                .param("id", id)
                .query(this::mapManifest)
                .optional();
    }

    /**
     * 推进联单状态并清除执行中标记；仅当当前状态匹配时才更新，返回更新行数。
     */
    public int transitionStatus(long manifestId, String fromStatus, String toStatus) {
        return jdbc.sql("""
                        UPDATE manifest SET status = :toStatus, open_flag = NULL
                        WHERE id = :id AND status = :fromStatus
                        """)
                .param("toStatus", toStatus)
                .param("id", manifestId)
                .param("fromStatus", fromStatus)
                .update();
    }

    /**
     * 插入回执并返回自增 id；request_id 与 manifest_id 唯一约束由数据库保证。
     */
    public long insertReceipt(String requestId, String fingerprint, long manifestId, BigDecimal netKg,
                              BigDecimal deviationPct, String result, Instant receivedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO receipt
                            (request_id, fingerprint, manifest_id, net_kg, deviation_pct, result, received_at)
                        VALUES
                            (:requestId, :fingerprint, :manifestId, :netKg, :deviationPct, :result, :receivedAt)
                        """)
                .param("requestId", requestId)
                .param("fingerprint", fingerprint)
                .param("manifestId", manifestId)
                .param("netKg", netKg)
                .param("deviationPct", deviationPct)
                .param("result", result)
                .param("receivedAt", Timestamp.from(receivedAt))
                .update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("回执插入未返回自增主键");
        }
        return key.longValue();
    }

    public Optional<ReceiptRow> findReceiptByRequestId(String requestId) {
        return jdbc.sql("""
                        SELECT id, request_id, fingerprint, manifest_id, net_kg, deviation_pct, result, received_at
                        FROM receipt WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((rs, rowNum) -> new ReceiptRow(
                        rs.getLong("id"),
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
                        rs.getLong("manifest_id"),
                        rs.getBigDecimal("net_kg"),
                        rs.getBigDecimal("deviation_pct"),
                        rs.getString("result"),
                        rs.getTimestamp("received_at").toInstant()))
                .optional();
    }

    /**
     * 插入异常核查留痕并返回自增 id；request_id 与 manifest_id 唯一约束由数据库保证。
     */
    public long insertReview(String requestId, String fingerprint, long manifestId,
                             String conclusion, String note, Instant reviewedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO exception_review
                            (request_id, fingerprint, manifest_id, conclusion, note, reviewed_at)
                        VALUES
                            (:requestId, :fingerprint, :manifestId, :conclusion, :note, :reviewedAt)
                        """)
                .param("requestId", requestId)
                .param("fingerprint", fingerprint)
                .param("manifestId", manifestId)
                .param("conclusion", conclusion)
                .param("note", note)
                .param("reviewedAt", Timestamp.from(reviewedAt))
                .update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("核查记录插入未返回自增主键");
        }
        return key.longValue();
    }

    public Optional<ReviewRow> findReviewByRequestId(String requestId) {
        return jdbc.sql("""
                        SELECT id, request_id, fingerprint, manifest_id, conclusion, note, reviewed_at
                        FROM exception_review WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query((rs, rowNum) -> new ReviewRow(
                        rs.getLong("id"),
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
                        rs.getLong("manifest_id"),
                        rs.getString("conclusion"),
                        rs.getString("note"),
                        rs.getTimestamp("reviewed_at").toInstant()))
                .optional();
    }

    /**
     * 日结汇总：进场量按联单登记时间归日，出场量按回执时间归日，按品类分组。
     * 直接由联单与回执明细实时复算，重复查询结果稳定。
     */
    public List<SettlementRow> summarizeDaily(Instant dayStart, Instant dayEnd) {
        return jdbc.sql("""
                        SELECT category,
                               SUM(inbound_count) AS inbound_count,
                               SUM(inbound_net_kg) AS inbound_net_kg,
                               SUM(outbound_count) AS outbound_count,
                               SUM(outbound_net_kg) AS outbound_net_kg
                        FROM (
                            SELECT m.category AS category,
                                   COUNT(*) AS inbound_count,
                                   SUM(m.net_kg) AS inbound_net_kg,
                                   0 AS outbound_count,
                                   CAST(0 AS DECIMAL(14,3)) AS outbound_net_kg
                            FROM manifest m
                            WHERE m.created_at >= :dayStart AND m.created_at < :dayEnd
                            GROUP BY m.category
                            UNION ALL
                            SELECT m.category AS category,
                                   0 AS inbound_count,
                                   CAST(0 AS DECIMAL(14,3)) AS inbound_net_kg,
                                   COUNT(*) AS outbound_count,
                                   SUM(r.net_kg) AS outbound_net_kg
                            FROM receipt r
                            JOIN manifest m ON m.id = r.manifest_id
                            WHERE r.received_at >= :dayStart AND r.received_at < :dayEnd
                            GROUP BY m.category
                        ) t
                        GROUP BY category
                        ORDER BY category
                        """)
                .param("dayStart", Timestamp.from(dayStart))
                .param("dayEnd", Timestamp.from(dayEnd))
                .query((rs, rowNum) -> new SettlementRow(
                        rs.getString("category"),
                        rs.getLong("inbound_count"),
                        rs.getBigDecimal("inbound_net_kg"),
                        rs.getLong("outbound_count"),
                        rs.getBigDecimal("outbound_net_kg")))
                .list();
    }

    private ManifestRow mapManifest(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ManifestRow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("vehicle_id"),
                rs.getString("community"),
                rs.getString("category"),
                rs.getBigDecimal("gross_kg"),
                rs.getBigDecimal("tare_kg"),
                rs.getBigDecimal("net_kg"),
                rs.getLong("facility_id"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }
}
