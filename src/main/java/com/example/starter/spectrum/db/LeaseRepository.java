package com.example.starter.spectrum.db;

import com.example.starter.spectrum.LeaseMode;
import com.example.starter.spectrum.domain.ActiveLease;
import com.example.starter.spectrum.domain.LeaseAggregate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 租约持久化仓储：所有 SQL 均参数化，时间列统一按 UTC 读写。
 */
@Repository
public class LeaseRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LeaseRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 幂等请求落定记录。 */
    public record IdempotentRecord(String requestId, String requestKind, String leaseId,
                                   String fingerprint, int httpStatus, String responseBody) {
    }

    /**
     * 获取协调写锁（单行 SELECT ... FOR UPDATE），在写事务内串行化全部创建/修改/取消。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquireCoordinationLock() {
        jdbc.getJdbcOperations().queryForObject(
                "SELECT id FROM lease_coordination_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    public void insertIdempotentRecord(IdempotentRecord record, Instant now) {
        jdbc.update("""
                        INSERT INTO lease_request
                            (request_id, request_kind, lease_id, fingerprint_sha256,
                             http_status, response_body, created_at_utc)
                        VALUES (:requestId, :kind, :leaseId, :fingerprint,
                                :status, :body, :createdAt)""",
                new MapSqlParameterSource()
                        .addValue("requestId", record.requestId())
                        .addValue("kind", record.requestKind())
                        .addValue("leaseId", record.leaseId())
                        .addValue("fingerprint", record.fingerprint())
                        .addValue("status", record.httpStatus())
                        .addValue("body", record.responseBody())
                        .addValue("createdAt", Timestamp.from(now)));
    }

    public Optional<IdempotentRecord> findIdempotentRecord(String requestId) {
        List<IdempotentRecord> list = jdbc.query("""
                        SELECT request_id, request_kind, lease_id, fingerprint_sha256,
                               http_status, response_body
                        FROM lease_request WHERE request_id = :requestId""",
                new MapSqlParameterSource("requestId", requestId),
                (rs, n) -> new IdempotentRecord(
                        rs.getString("request_id"), rs.getString("request_kind"),
                        rs.getString("lease_id"), rs.getString("fingerprint_sha256"),
                        rs.getInt("http_status"), rs.getString("response_body")));
        return list.stream().findFirst();
    }

    /**
     * 创建租约首版本：主表、版本表、区域集合、CREATED 事件在同一事务内落库。
     */
    public void insertLease(String leaseId, int version, Instant start, Instant end,
                            java.math.BigDecimal freqLowMHz, java.math.BigDecimal freqHighMHz,
                            String mode, java.math.BigDecimal eirpMw, Set<String> regions,
                            Instant now) {
        jdbc.update("""
                        INSERT INTO lease (id, current_version, status, created_at_utc, updated_at_utc)
                        VALUES (:id, :version, 'ACTIVE', :now, :now)""",
                new MapSqlParameterSource()
                        .addValue("id", leaseId).addValue("version", version)
                        .addValue("now", Timestamp.from(now)));
        insertVersionRow(leaseId, version, start, end, freqLowMHz, freqHighMHz, mode, eirpMw, now);
        insertRegions(leaseId, version, regions);
        insertEvent(leaseId, version, "CREATED", "租约创建", now);
    }

    /**
     * 追加新版本并切换当前指针，历史版本保留；写 MODIFIED 事件。
     */
    public void appendVersion(String leaseId, int version, Instant start, Instant end,
                              java.math.BigDecimal freqLowMHz, java.math.BigDecimal freqHighMHz,
                              String mode, java.math.BigDecimal eirpMw, Set<String> regions,
                              Instant now) {
        jdbc.update("""
                        UPDATE lease SET current_version = :version, updated_at_utc = :now
                        WHERE id = :id""",
                new MapSqlParameterSource()
                        .addValue("id", leaseId).addValue("version", version)
                        .addValue("now", Timestamp.from(now)));
        insertVersionRow(leaseId, version, start, end, freqLowMHz, freqHighMHz, mode, eirpMw, now);
        insertRegions(leaseId, version, regions);
        insertEvent(leaseId, version, "MODIFIED", "租约修改为版本 " + version, now);
    }

    /**
     * 取消租约：主表与当前版本置 CANCELLED 并记录取消生效时刻，写 CANCELLED 事件；
     * 历史版本行保持不变。
     */
    public void markCancelled(String leaseId, int version, Instant now) {
        jdbc.update("""
                        UPDATE lease SET status = 'CANCELLED', updated_at_utc = :now
                        WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", leaseId)
                        .addValue("now", Timestamp.from(now)));
        jdbc.update("""
                        UPDATE lease_version
                        SET status = 'CANCELLED', cancelled_at_utc = :now
                        WHERE lease_id = :id AND version = :version""",
                new MapSqlParameterSource().addValue("id", leaseId).addValue("version", version)
                        .addValue("now", Timestamp.from(now)));
        insertEvent(leaseId, version, "CANCELLED", "租约取消，自此时刻起不再有效", now);
    }

    public Optional<LeaseAggregate> loadLease(String leaseId) {
        List<LeaseAggregate> leases = jdbc.query("""
                        SELECT l.id AS lease_id, l.current_version, l.status AS lease_status,
                               v.start_time_utc, v.end_time_utc, v.freq_low_mhz, v.freq_high_mhz,
                               v.lease_mode, v.eirp_mw, v.cancelled_at_utc
                        FROM lease l
                        JOIN lease_version v
                          ON v.lease_id = l.id AND v.version = l.current_version
                        WHERE l.id = :id""",
                new MapSqlParameterSource("id", leaseId), aggregateMapper());
        if (leases.isEmpty()) {
            return Optional.empty();
        }
        LeaseAggregate base = leases.get(0);
        return Optional.of(withRelations(base));
    }

    /**
     * 加载与给定时间窗口、区域集合可能相遇的全部活动租约当前版本。
     * 半开区间正长度交集：v.start &lt; windowEnd AND v.end &gt; windowStart。
     */
    public List<ActiveLease> loadActiveLeases(Instant windowStart, Instant windowEnd,
                                              Set<String> regions) {
        if (regions.isEmpty()) {
            return List.of();
        }
        List<ActiveLease> rows = jdbc.query("""
                        SELECT l.id AS lease_id, l.current_version,
                               v.start_time_utc, v.end_time_utc,
                               v.freq_low_mhz, v.freq_high_mhz,
                               v.lease_mode, v.eirp_mw
                        FROM lease l
                        JOIN lease_version v
                          ON v.lease_id = l.id AND v.version = l.current_version
                        WHERE l.status = 'ACTIVE'
                          AND v.start_time_utc < :windowEnd
                          AND v.end_time_utc > :windowStart
                          AND EXISTS (
                              SELECT 1 FROM lease_region r
                              WHERE r.lease_id = l.id AND r.version = l.current_version
                                AND r.region_cell IN (:regions))""",
                new MapSqlParameterSource()
                        .addValue("windowStart", Timestamp.from(windowStart))
                        .addValue("windowEnd", Timestamp.from(windowEnd))
                        .addValue("regions", new TreeSet<>(regions)),
                (rs, n) -> new ActiveLease(
                        rs.getString("lease_id"), rs.getInt("current_version"),
                        rs.getTimestamp("start_time_utc").toInstant(),
                        rs.getTimestamp("end_time_utc").toInstant(),
                        rs.getBigDecimal("freq_low_mhz"), rs.getBigDecimal("freq_high_mhz"),
                        LeaseMode.valueOf(rs.getString("lease_mode")),
                        rs.getBigDecimal("eirp_mw"), new HashSet<>()));
        fillRegions(rows);
        return rows;
    }

    /**
     * 指定 UTC 时刻的活动租约快照：取该时刻已落定的最新版本，
     * 满足 start &lt;= t &lt; end 且（未取消或 t 早于取消生效时刻）。
     * 已取消租约在取消之前的历史时刻仍可出现在快照中。
     */
    public List<LeaseAggregate> loadActiveAt(Instant at) {
        List<VersionRow> versions = jdbc.query("""
                        SELECT v.lease_id, v.version, v.start_time_utc, v.end_time_utc,
                               v.freq_low_mhz, v.freq_high_mhz, v.lease_mode, v.eirp_mw,
                               v.status, v.cancelled_at_utc, v.created_at_utc
                        FROM lease_version v
                        WHERE v.created_at_utc <= :at
                        ORDER BY v.lease_id, v.version""",
                new MapSqlParameterSource("at", Timestamp.from(at)),
                (rs, n) -> new VersionRow(
                        rs.getString("lease_id"), rs.getInt("version"),
                        rs.getTimestamp("start_time_utc").toInstant(),
                        rs.getTimestamp("end_time_utc").toInstant(),
                        rs.getBigDecimal("freq_low_mhz"), rs.getBigDecimal("freq_high_mhz"),
                        LeaseMode.valueOf(rs.getString("lease_mode")),
                        rs.getBigDecimal("eirp_mw"), rs.getString("status"),
                        rs.getTimestamp("cancelled_at_utc") == null ? null
                                : rs.getTimestamp("cancelled_at_utc").toInstant()));

        // 每个租约取 created_at_utc <= t 的最大版本
        Map<String, VersionRow> effective = new LinkedHashMap<>();
        for (VersionRow row : versions) {
            effective.merge(row.leaseId(), row, (older, newer) -> newer);
        }
        List<LeaseAggregate> result = new ArrayList<>();
        for (VersionRow row : effective.values()) {
            boolean withinWindow = !at.isBefore(row.start()) && at.isBefore(row.end());
            boolean notYetCancelled = row.cancelledAt() == null || at.isBefore(row.cancelledAt());
            if (withinWindow && notYetCancelled) {
                result.add(toAggregate(row));
            }
        }
        return result;
    }

    private void insertVersionRow(String leaseId, int version, Instant start, Instant end,
                                  java.math.BigDecimal freqLowMHz, java.math.BigDecimal freqHighMHz,
                                  String mode, java.math.BigDecimal eirpMw, Instant now) {
        jdbc.update("""
                        INSERT INTO lease_version
                            (lease_id, version, start_time_utc, end_time_utc,
                             freq_low_mhz, freq_high_mhz, lease_mode, eirp_mw,
                             status, created_at_utc, cancelled_at_utc)
                        VALUES (:id, :version, :start, :end, :low, :high, :mode, :eirp,
                                'ACTIVE', :now, NULL)""",
                new MapSqlParameterSource()
                        .addValue("id", leaseId).addValue("version", version)
                        .addValue("start", Timestamp.from(start)).addValue("end", Timestamp.from(end))
                        .addValue("low", freqLowMHz).addValue("high", freqHighMHz)
                        .addValue("mode", mode).addValue("eirp", eirpMw)
                        .addValue("now", Timestamp.from(now)));
    }

    private void insertRegions(String leaseId, int version, Set<String> regions) {
        MapSqlParameterSource[] batch = new TreeSet<>(regions).stream()
                .map(cell -> new MapSqlParameterSource()
                        .addValue("id", leaseId).addValue("version", version)
                        .addValue("cell", cell))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                        INSERT INTO lease_region (lease_id, version, region_cell)
                        VALUES (:id, :version, :cell)""",
                batch);
    }

    private void insertEvent(String leaseId, int version, String type, String detail, Instant now) {
        jdbc.update("""
                        INSERT INTO lease_event (lease_id, version, event_type, detail, created_at_utc)
                        VALUES (:id, :version, :type, :detail, :now)""",
                new MapSqlParameterSource()
                        .addValue("id", leaseId).addValue("version", version)
                        .addValue("type", type).addValue("detail", detail)
                        .addValue("now", Timestamp.from(now)));
    }

    private RowMapper<LeaseAggregate> aggregateMapper() {
        return (rs, n) -> new LeaseAggregate(
                rs.getString("lease_id"), rs.getInt("current_version"),
                rs.getString("lease_status"),
                rs.getTimestamp("start_time_utc").toInstant(),
                rs.getTimestamp("end_time_utc").toInstant(),
                rs.getBigDecimal("freq_low_mhz"), rs.getBigDecimal("freq_high_mhz"),
                LeaseMode.valueOf(rs.getString("lease_mode")),
                rs.getBigDecimal("eirp_mw"), new ArrayList<>(),
                rs.getTimestamp("cancelled_at_utc") == null ? null
                        : rs.getTimestamp("cancelled_at_utc").toInstant(),
                new ArrayList<>());
    }

    private void fillRegions(List<ActiveLease> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<String> leaseIds = rows.stream().map(ActiveLease::leaseId).toList();
        Map<String, Set<String>> byLease = new LinkedHashMap<>();
        jdbc.query("""
                        SELECT r.lease_id, r.region_cell
                        FROM lease_region r
                        JOIN lease l ON l.id = r.lease_id AND l.current_version = r.version
                        WHERE r.lease_id IN (:ids)""",
                new MapSqlParameterSource("ids", leaseIds),
                rs -> {
                    byLease.computeIfAbsent(rs.getString("lease_id"), k -> new HashSet<>())
                            .add(rs.getString("region_cell"));
                });
        List<ActiveLease> filled = new ArrayList<>(rows.size());
        for (ActiveLease row : rows) {
            Set<String> cells = byLease.getOrDefault(row.leaseId(), Set.of());
            filled.add(new ActiveLease(row.leaseId(), row.version(), row.start(), row.end(),
                    row.freqLowMHz(), row.freqHighMHz(), row.mode(), row.eirpMw(), cells));
        }
        rows.clear();
        rows.addAll(filled);
    }

    private LeaseAggregate toAggregate(VersionRow row) {
        LeaseAggregate base = new LeaseAggregate(
                row.leaseId(), row.version(), row.status(),
                row.start(), row.end(), row.freqLowMHz(), row.freqHighMHz(),
                row.leaseMode(), row.eirpMw(), new ArrayList<>(),
                row.cancelledAt(), new ArrayList<>());
        return withRelations(base);
    }

    private LeaseAggregate withRelations(LeaseAggregate base) {
        List<String> regions = new java.util.ArrayList<>(jdbc.query(
                        "SELECT region_cell FROM lease_region WHERE lease_id = :id AND version = :version",
                        new MapSqlParameterSource().addValue("id", base.leaseId())
                                .addValue("version", base.currentVersion()),
                        (rs, n) -> rs.getString("region_cell")));
        regions.sort(String::compareTo);
        List<LeaseAggregate.Event> events = jdbc.query("""
                        SELECT version, event_type, detail, created_at_utc FROM lease_event
                        WHERE lease_id = :id ORDER BY id""",
                new MapSqlParameterSource("id", base.leaseId()),
                (rs, n) -> new LeaseAggregate.Event(
                        rs.getInt("version"), rs.getString("event_type"),
                        rs.getString("detail"), rs.getTimestamp("created_at_utc").toInstant()));
        return new LeaseAggregate(base.leaseId(), base.currentVersion(), base.status(),
                base.start(), base.end(), base.freqLowMHz(), base.freqHighMHz(), base.mode(),
                base.eirpMw(), regions, base.cancelledAtUtc(), events);
    }

    /** lease_version 行的内部载体。 */
    private record VersionRow(String leaseId, int version, Instant start, Instant end,
                              java.math.BigDecimal freqLowMHz, java.math.BigDecimal freqHighMHz,
                              LeaseMode leaseMode, java.math.BigDecimal eirpMw,
                              String status, Instant cancelledAt) {
    }
}
