package com.example.starter.spectrum.repo;

import com.example.starter.spectrum.domain.EventType;
import com.example.starter.spectrum.domain.LeaseHeader;
import com.example.starter.spectrum.domain.LeaseMode;
import com.example.starter.spectrum.domain.LeaseSpec;
import com.example.starter.spectrum.domain.LeaseStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * 租约数据访问：租约头、版本、区域单元、事件与幂等记录。
 * 所有 SQL 均为参数化语句，兼容 H2 MySQL 模式与 MySQL。
 */
@Repository
public class LeaseRepository {

    private final JdbcTemplate jdbc;

    public LeaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 对协调锁单行加写锁，必须在事务内调用；锁持有至事务提交/回滚，
     * 串行化所有创建、修改、取消事务。
     */
    public void lockCoordination() {
        jdbc.queryForObject("SELECT id FROM coord_lock WHERE id = 1 FOR UPDATE", Integer.class);
    }

    private static final RowMapper<LeaseHeader> HEADER_MAPPER = (rs, n) -> {
        long cancelTime = rs.getLong("cancel_time");
        return new LeaseHeader(
                rs.getLong("lease_id"),
                rs.getInt("current_version"),
                LeaseStatus.valueOf(rs.getString("status")),
                rs.getLong("create_time"),
                rs.getLong("update_time"),
                rs.getLong("cancel_time") == 0 && rs.wasNull() ? null : cancelTime);
    };

    /** 按主键读取租约头。 */
    public LeaseHeader findHeader(long leaseId) {
        List<LeaseHeader> rows = jdbc.query(
                "SELECT lease_id, current_version, status, create_time, update_time, cancel_time "
                        + "FROM lease WHERE lease_id = ?",
                HEADER_MAPPER, leaseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 读取租约指定版本；版本不存在返回 null。 */
    public LeaseVersionRecord findVersionRecord(long leaseId, int version) {
        List<LeaseVersionRecord> rows = jdbc.query(
                "SELECT lease_id, version, start_time, end_time, freq_low_mhz, freq_high_mhz, "
                        + "mode, eirp_mw, request_id, created_at "
                        + "FROM lease_version WHERE lease_id = ? AND version = ?",
                VERSION_MAPPER, leaseId, version);
        if (rows.isEmpty()) {
            return null;
        }
        LeaseVersionRecord record = rows.get(0);
        return new LeaseVersionRecord(record.spec(), record.requestId(), record.createdAt(),
                record.version(), record.leaseId(), findCells(leaseId, version));
    }

    /** 读取租约头的全部历史版本号（升序）。 */
    public List<Integer> findVersionNumbers(long leaseId) {
        return jdbc.queryForList(
                "SELECT version FROM lease_version WHERE lease_id = ? ORDER BY version",
                Integer.class, leaseId);
    }

    /**
     * 读取全部租约头及其当前版本（含已取消租约，由服务层按 cancel_time 裁剪时间区间）。
     * 区域单元在一次批量查询中加载并按 (租约, 版本) 分组。
     */
    public List<CurrentLeaseRow> findAllCurrent() {
        List<VersionRow> versionRows = jdbc.query(
                "SELECT h.lease_id AS lease_id, h.current_version AS current_version, "
                        + "h.status AS status, h.create_time AS create_time, "
                        + "h.update_time AS update_time, h.cancel_time AS cancel_time, "
                        + "v.start_time AS start_time, v.end_time AS end_time, "
                        + "v.freq_low_mhz AS freq_low_mhz, v.freq_high_mhz AS freq_high_mhz, "
                        + "v.mode AS mode, v.eirp_mw AS eirp_mw "
                        + "FROM lease h JOIN lease_version v "
                        + "ON v.lease_id = h.lease_id AND v.version = h.current_version",
                (rs, n) -> new VersionRow(
                        rs.getLong("lease_id"),
                        rs.getInt("current_version"),
                        LeaseStatus.valueOf(rs.getString("status")),
                        rs.getLong("create_time"),
                        rs.getLong("update_time"),
                        rs.getLong("cancel_time") == 0 && rs.wasNull() ? null : rs.getLong("cancel_time"),
                        rs.getLong("start_time"),
                        rs.getLong("end_time"),
                        rs.getDouble("freq_low_mhz"),
                        rs.getDouble("freq_high_mhz"),
                        LeaseMode.valueOf(rs.getString("mode")),
                        rs.getDouble("eirp_mw")));

        Map<CellKey, SortedSet<String>> cellsByKey = loadAllCells();

        List<CurrentLeaseRow> result = new ArrayList<>();
        for (VersionRow row : versionRows) {
            LeaseHeader header = new LeaseHeader(row.leaseId(), row.version(), row.status(),
                    row.createTime(), row.updateTime(), row.cancelTime());
            LeaseSpec spec = new LeaseSpec(row.startTime(), row.endTime(),
                    row.freqLowMhz(), row.freqHighMhz(), row.mode(), row.eirpMw(),
                    cellsByKey.getOrDefault(new CellKey(row.leaseId(), row.version()), new TreeSet<>()));
            result.add(new CurrentLeaseRow(header, spec));
        }
        return result;
    }

    private Map<CellKey, SortedSet<String>> loadAllCells() {
        Map<CellKey, SortedSet<String>> grouped = new HashMap<>();
        jdbc.query("SELECT lease_id, version, cell FROM lease_cell",
                rs -> {
                    CellKey key = new CellKey(rs.getLong(1), rs.getInt(2));
                    grouped.computeIfAbsent(key, k -> new TreeSet<>()).add(rs.getString(3));
                });
        return grouped;
    }

    /** 新建租约头（初始版本 1、活动状态）并返回自增 ID。 */
    public long insertHeader(long now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO lease (current_version, status, create_time, update_time, cancel_time) "
                            + "VALUES (1, 'ACTIVE', ?, ?, NULL)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, now);
            ps.setLong(2, now);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("failed to obtain generated lease id");
        }
        return key.longValue();
    }

    /** 写入一个版本及其全部区域单元（调用方须保证同事务）。 */
    public void insertVersion(long leaseId, int version, LeaseSpec spec, String requestId, long now) {
        jdbc.update("INSERT INTO lease_version (lease_id, version, start_time, end_time, "
                        + "freq_low_mhz, freq_high_mhz, mode, eirp_mw, request_id, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                leaseId, version, spec.startTime(), spec.endTime(),
                spec.freqLowMhz(), spec.freqHighMhz(), spec.mode().name(), spec.eirpMw(),
                requestId, now);
        for (String cell : spec.cells()) {
            jdbc.update("INSERT INTO lease_cell (lease_id, version, cell) VALUES (?, ?, ?)",
                    leaseId, version, cell);
        }
    }

    /** 更新租约头当前版本与更新时间。 */
    public void updateCurrentVersion(long leaseId, int version, long now) {
        int updated = jdbc.update(
                "UPDATE lease SET current_version = ?, update_time = ? WHERE lease_id = ?",
                version, now, leaseId);
        if (updated != 1) {
            throw new IllegalStateException("expected 1 header update, got " + updated);
        }
    }

    /** 取消租约：置状态、取消时间与更新时间。 */
    public void markCancelled(long leaseId, long cancelTime, long now) {
        int updated = jdbc.update(
                "UPDATE lease SET status = 'CANCELLED', cancel_time = ?, update_time = ? "
                        + "WHERE lease_id = ? AND status = 'ACTIVE'",
                cancelTime, now, leaseId);
        if (updated != 1) {
            throw new IllegalStateException("expected 1 active lease to cancel, got " + updated);
        }
    }

    /** 追加一条领域事件。 */
    public void insertEvent(long leaseId, int version, String requestId, EventType type, long now) {
        jdbc.update("INSERT INTO lease_event (lease_id, version, request_id, type, event_time) "
                        + "VALUES (?, ?, ?, ?, ?)",
                leaseId, version, requestId, type.name(), now);
    }

    /** 统计租约事件数（用于验证重复取消不新增事件）。 */
    public int countEvents(long leaseId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lease_event WHERE lease_id = ?", Integer.class, leaseId);
        return count == null ? 0 : count;
    }

    /**
     * 按请求幂等键查询其首次产生的租约版本（用于幂等回放首次结果）；
     * 纯取消请求不产生新版本，返回 null。
     */
    public Integer findEventVersionByRequest(String requestId) {
        List<Integer> versions = jdbc.queryForList(
                "SELECT version FROM lease_event WHERE request_id = ? ORDER BY id LIMIT 1",
                Integer.class, requestId);
        return versions.isEmpty() ? null : versions.get(0);
    }

    /** 按幂等键读取记录；不存在返回 null。 */
    public IdempotencyRow findIdempotency(String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT request_id, fingerprint, http_status, result_lease_id, error_code, "
                        + "error_body, created_at FROM idempotency_record WHERE request_id = ?",
                (rs, n) -> new IdempotencyRow(
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
                        rs.getInt("http_status"),
                        (Long) rs.getObject("result_lease_id"),
                        rs.getString("error_code"),
                        rs.getString("error_body"),
                        rs.getLong("created_at")),
                requestId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 写入幂等记录（成功或业务失败均在协调事务内提交）。 */
    public void insertIdempotency(String requestId, String fingerprint, int httpStatus,
                                  String errorCode, String errorBody, Long resultLeaseId, long now) {
        jdbc.update("INSERT INTO idempotency_record (request_id, fingerprint, http_status, "
                        + "result_lease_id, error_code, error_body, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                requestId, fingerprint, httpStatus, resultLeaseId, errorCode, errorBody, now);
    }

    private SortedSet<String> findCells(long leaseId, int version) {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT cell FROM lease_cell WHERE lease_id = ? AND version = ? ORDER BY cell",
                String.class, leaseId, version));
    }

    private static final RowMapper<LeaseVersionRecord> VERSION_MAPPER = (rs, n) -> {
        LeaseSpec spec = new LeaseSpec(
                rs.getLong("start_time"), rs.getLong("end_time"),
                rs.getDouble("freq_low_mhz"), rs.getDouble("freq_high_mhz"),
                LeaseMode.valueOf(rs.getString("mode")), rs.getDouble("eirp_mw"),
                new TreeSet<>());
        return new LeaseVersionRecord(spec, rs.getString("request_id"),
                rs.getLong("created_at"), rs.getInt("version"), rs.getLong("lease_id"),
                new TreeSet<>());
    };

    private record CellKey(long leaseId, int version) {
    }

    private record VersionRow(long leaseId, int version, LeaseStatus status,
                              long createTime, long updateTime, Long cancelTime,
                              long startTime, long endTime,
                              double freqLowMhz, double freqHighMhz,
                              LeaseMode mode, double eirpMw) {
    }

    /**
     * 租约头 + 当前版本规格（规格中携带区域单元集合）。
     */
    public record CurrentLeaseRow(LeaseHeader header, LeaseSpec spec) {
    }

    /**
     * 租约版本记录（含区域单元）。
     */
    public record LeaseVersionRecord(LeaseSpec spec, String requestId, long createdAt,
                                     int version, long leaseId, SortedSet<String> cells) {
    }

    /**
     * 幂等记录行。
     */
    public record IdempotencyRow(String requestId, String fingerprint, int httpStatus,
                                 Long resultLeaseId, String errorCode, String errorBody,
                                 long createdAt) {
    }
}
