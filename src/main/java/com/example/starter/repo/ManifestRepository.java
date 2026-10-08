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
 * 收运联单、去向版本链、处理回执、迟到回执证据、异常核查与日结汇总的数据访问。
 */
@Repository
public class ManifestRepository {

    private final JdbcClient jdbc;

    public ManifestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 联单行。重量单位 kg，时间均为 UTC。
     *
     * @param destinationVersion 当前去向版本，自 1 起，每次改投 +1
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
            int destinationVersion,
            String status,
            Instant createdAt) {
    }

    /**
     * 回执行。facilityId / destinationVersion 为受控回执声称的去向，旧式回执为 null。
     */
    public record ReceiptRow(
            long id,
            String requestId,
            String fingerprint,
            long manifestId,
            BigDecimal netKg,
            BigDecimal deviationPct,
            String result,
            Long facilityId,
            Integer destinationVersion,
            Instant receivedAt) {
    }

    /**
     * 去向版本链行。
     *
     * @param requestId 改投幂等请求号；初始去向（版本 1）为 null
     */
    public record DestinationRow(
            long id,
            long manifestId,
            int version,
            long facilityId,
            String requestId,
            String fingerprint,
            Instant createdAt) {
    }

    /**
     * 迟到回执证据行：仅留存，不闭环、不计入接收量。
     */
    public record LateReceiptRow(
            long id,
            String requestId,
            String fingerprint,
            long manifestId,
            long facilityId,
            int destinationVersion,
            BigDecimal netKg,
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
     * 去向版本由数据库默认值置 1。
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
                               gross_kg, tare_kg, net_kg, facility_id, destination_version, status, created_at
                        FROM manifest WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(this::mapManifest)
                .optional();
    }

    public Optional<ManifestRow> findManifestById(long id) {
        return jdbc.sql("""
                        SELECT id, request_id, fingerprint, vehicle_id, community, category,
                               gross_kg, tare_kg, net_kg, facility_id, destination_version, status, created_at
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
     * 带去向版本守卫的状态推进：仅当状态与去向版本均未变化时才更新，
     * 用于回执闭环与并发改投互斥（改投先成则本更新命中 0 行）。
     */
    public int transitionStatusGuarded(long manifestId, String fromStatus, String toStatus,
                                       int expectedDestinationVersion) {
        return jdbc.sql("""
                        UPDATE manifest SET status = :toStatus, open_flag = NULL
                        WHERE id = :id AND status = :fromStatus AND destination_version = :expectedVersion
                        """)
                .param("toStatus", toStatus)
                .param("id", manifestId)
                .param("fromStatus", fromStatus)
                .param("expectedVersion", expectedDestinationVersion)
                .update();
    }

    /**
     * 改投 CAS：仅当联单仍在途且去向版本等于 expectedVersion 时，
     * 将当前去向设施切到新设施并把去向版本 +1，返回更新行数。
     */
    public int rerouteGuarded(long manifestId, int expectedVersion, long newFacilityId) {
        return jdbc.sql("""
                        UPDATE manifest
                        SET facility_id = :newFacilityId, destination_version = destination_version + 1
                        WHERE id = :id AND status = 'IN_TRANSIT' AND destination_version = :expectedVersion
                        """)
                .param("newFacilityId", newFacilityId)
                .param("id", manifestId)
                .param("expectedVersion", expectedVersion)
                .update();
    }

    /**
     * 追加一条去向版本链记录；(manifest_id, version) 与 request_id 唯一约束由数据库保证。
     */
    public void insertDestination(long manifestId, int version, long facilityId,
                                  String requestId, String fingerprint, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO manifest_destination
                            (manifest_id, version, facility_id, request_id, fingerprint, created_at)
                        VALUES
                            (:manifestId, :version, :facilityId, :requestId, :fingerprint, :createdAt)
                        """)
                .param("manifestId", manifestId)
                .param("version", version)
                .param("facilityId", facilityId)
                .param("requestId", requestId, java.sql.Types.VARCHAR)
                .param("fingerprint", fingerprint, java.sql.Types.VARCHAR)
                .param("createdAt", Timestamp.from(createdAt))
                .update();
    }

    public Optional<DestinationRow> findDestinationByRequestId(String requestId) {
        return jdbc.sql("""
                        SELECT id, manifest_id, version, facility_id, request_id, fingerprint, created_at
                        FROM manifest_destination WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(this::mapDestination)
                .optional();
    }

    public List<DestinationRow> listDestinations(long manifestId) {
        return jdbc.sql("""
                        SELECT id, manifest_id, version, facility_id, request_id, fingerprint, created_at
                        FROM manifest_destination WHERE manifest_id = :manifestId ORDER BY version
                        """)
                .param("manifestId", manifestId)
                .query(this::mapDestination)
                .list();
    }

    /**
     * 插入回执并返回自增 id；request_id 与 manifest_id 唯一约束由数据库保证。
     * facilityId / destinationVersion 为受控回执的去向声称，旧式回执传 null。
     */
    public long insertReceipt(String requestId, String fingerprint, long manifestId, BigDecimal netKg,
                              BigDecimal deviationPct, String result, Long facilityId,
                              Integer destinationVersion, Instant receivedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO receipt
                            (request_id, fingerprint, manifest_id, net_kg, deviation_pct, result,
                             facility_id, destination_version, received_at)
                        VALUES
                            (:requestId, :fingerprint, :manifestId, :netKg, :deviationPct, :result,
                             :facilityId, :destinationVersion, :receivedAt)
                        """)
                .param("requestId", requestId)
                .param("fingerprint", fingerprint)
                .param("manifestId", manifestId)
                .param("netKg", netKg)
                .param("deviationPct", deviationPct)
                .param("result", result)
                .param("facilityId", facilityId, java.sql.Types.BIGINT)
                .param("destinationVersion", destinationVersion, java.sql.Types.INTEGER)
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
                        SELECT id, request_id, fingerprint, manifest_id, net_kg, deviation_pct, result,
                               facility_id, destination_version, received_at
                        FROM receipt WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(this::mapReceipt)
                .optional();
    }

    public List<ReceiptRow> listReceipts(long manifestId) {
        return jdbc.sql("""
                        SELECT id, request_id, fingerprint, manifest_id, net_kg, deviation_pct, result,
                               facility_id, destination_version, received_at
                        FROM receipt WHERE manifest_id = :manifestId ORDER BY id
                        """)
                .param("manifestId", manifestId)
                .query(this::mapReceipt)
                .list();
    }

    /**
     * 登记一条迟到回执证据并返回自增 id；request_id 唯一约束由数据库保证。
     * 只存证据，不触碰联单状态与接收量。
     */
    public long insertLateReceipt(String requestId, String fingerprint, long manifestId, long facilityId,
                                  int destinationVersion, BigDecimal netKg, Instant receivedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO late_receipt
                            (request_id, fingerprint, manifest_id, facility_id, destination_version, net_kg, received_at)
                        VALUES
                            (:requestId, :fingerprint, :manifestId, :facilityId, :destinationVersion, :netKg, :receivedAt)
                        """)
                .param("requestId", requestId)
                .param("fingerprint", fingerprint)
                .param("manifestId", manifestId)
                .param("facilityId", facilityId)
                .param("destinationVersion", destinationVersion)
                .param("netKg", netKg)
                .param("receivedAt", Timestamp.from(receivedAt))
                .update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("迟到回执插入未返回自增主键");
        }
        return key.longValue();
    }

    public Optional<LateReceiptRow> findLateReceiptByRequestId(String requestId) {
        return jdbc.sql("""
                        SELECT id, request_id, fingerprint, manifest_id, facility_id, destination_version,
                               net_kg, received_at
                        FROM late_receipt WHERE request_id = :requestId
                        """)
                .param("requestId", requestId)
                .query(this::mapLateReceipt)
                .optional();
    }

    public List<LateReceiptRow> listLateReceipts(long manifestId) {
        return jdbc.sql("""
                        SELECT id, request_id, fingerprint, manifest_id, facility_id, destination_version,
                               net_kg, received_at
                        FROM late_receipt WHERE manifest_id = :manifestId ORDER BY id
                        """)
                .param("manifestId", manifestId)
                .query(this::mapLateReceipt)
                .list();
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
     * 日结汇总：进场量按联单登记时间归日，出场量按有效回执时间归日，按品类分组。
     * 迟到回执证据不参与出场量。直接由联单与回执明细实时复算，重复查询结果稳定。
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
                rs.getInt("destination_version"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant());
    }

    private ReceiptRow mapReceipt(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long facilityId = rs.getLong("facility_id");
        Long facilityIdBoxed = rs.wasNull() ? null : facilityId;
        int destinationVersion = rs.getInt("destination_version");
        Integer destinationVersionBoxed = rs.wasNull() ? null : destinationVersion;
        return new ReceiptRow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("manifest_id"),
                rs.getBigDecimal("net_kg"),
                rs.getBigDecimal("deviation_pct"),
                rs.getString("result"),
                facilityIdBoxed,
                destinationVersionBoxed,
                rs.getTimestamp("received_at").toInstant());
    }

    private DestinationRow mapDestination(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DestinationRow(
                rs.getLong("id"),
                rs.getLong("manifest_id"),
                rs.getInt("version"),
                rs.getLong("facility_id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getTimestamp("created_at").toInstant());
    }

    private LateReceiptRow mapLateReceipt(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new LateReceiptRow(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("fingerprint"),
                rs.getLong("manifest_id"),
                rs.getLong("facility_id"),
                rs.getInt("destination_version"),
                rs.getBigDecimal("net_kg"),
                rs.getTimestamp("received_at").toInstant());
    }
}
