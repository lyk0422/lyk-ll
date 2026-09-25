package com.example.starter.schedule.repository;

import com.example.starter.schedule.domain.ScheduleEntry;
import com.example.starter.schedule.domain.ScheduleVersion;
import com.example.starter.schedule.model.EntryStatus;
import com.example.starter.schedule.model.VersionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 编排版本与编排条目数据访问。
 */
@Repository
public class ScheduleRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert versionInsert;
    private final SimpleJdbcInsert entryInsert;

    public ScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.versionInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("schedule_version")
                .usingColumns("station_id", "day_key", "version_no", "status",
                        "published_at", "created_at")
                .usingGeneratedKeyColumns("id");
        this.entryInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("schedule_entry")
                .usingColumns("request_id", "satellite_id", "station_id", "window_id",
                        "version_id", "priority", "status", "start_time", "end_time",
                        "preempted_by_entry_id", "suggestion_window_id",
                        "suggestion_start_time", "suggestion_end_time",
                        "created_at", "updated_at")
                .usingGeneratedKeyColumns("id");
    }

    private static final RowMapper<ScheduleVersion> VERSION_MAPPER = (rs, n) -> {
        ScheduleVersion v = new ScheduleVersion();
        v.setId(rs.getLong("id"));
        v.setStationId(rs.getLong("station_id"));
        v.setDayKey(rs.getString("day_key"));
        v.setVersionNo(rs.getInt("version_no"));
        v.setStatus(VersionStatus.valueOf(rs.getString("status")));
        Timestamp published = rs.getTimestamp("published_at");
        v.setPublishedAt(published == null ? null : published.toInstant());
        v.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return v;
    };

    private static final RowMapper<ScheduleEntry> ENTRY_MAPPER = (rs, n) -> {
        ScheduleEntry e = new ScheduleEntry();
        e.setId(rs.getLong("id"));
        e.setRequestId(rs.getString("request_id"));
        e.setSatelliteId(rs.getLong("satellite_id"));
        e.setStationId(rs.getLong("station_id"));
        e.setWindowId(rs.getLong("window_id"));
        e.setVersionId(rs.getLong("version_id"));
        e.setPriority(rs.getInt("priority"));
        e.setStatus(EntryStatus.valueOf(rs.getString("status")));
        e.setStartTime(rs.getTimestamp("start_time").toInstant());
        e.setEndTime(rs.getTimestamp("end_time").toInstant());
        long by = rs.getLong("preempted_by_entry_id");
        e.setPreemptedByEntryId(rs.wasNull() ? null : by);
        long sw = rs.getLong("suggestion_window_id");
        e.setSuggestionWindowId(rs.wasNull() ? null : sw);
        Timestamp ss = rs.getTimestamp("suggestion_start_time");
        e.setSuggestionStartTime(ss == null ? null : ss.toInstant());
        Timestamp se = rs.getTimestamp("suggestion_end_time");
        e.setSuggestionEndTime(se == null ? null : se.toInstant());
        e.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        e.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return e;
    };

    private static final String VERSION_COLUMNS =
            "id, station_id, day_key, version_no, status, published_at, created_at ";

    private static final String ENTRY_COLUMNS = """
            id, request_id, satellite_id, station_id, window_id, version_id, priority,
            status, start_time, end_time, preempted_by_entry_id, suggestion_window_id,
            suggestion_start_time, suggestion_end_time, created_at, updated_at
            """;

    public Optional<ScheduleVersion> findVersion(long stationId, String dayKey, int versionNo) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + """
                         FROM schedule_version
                         WHERE station_id = ? AND day_key = ? AND version_no = ?""",
                VERSION_MAPPER, stationId, dayKey, versionNo).stream().findFirst();
    }

    public Optional<ScheduleVersion> findVersionById(long versionId) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + " FROM schedule_version WHERE id = ?",
                VERSION_MAPPER, versionId).stream().findFirst();
    }

    /**
     * 查询同站同日最新版本（含已发布与草稿），不存在返回空。
     */
    public Optional<ScheduleVersion> findLatestVersion(long stationId, String dayKey) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + """
                         FROM schedule_version
                         WHERE station_id = ? AND day_key = ?
                         ORDER BY version_no DESC LIMIT 1""",
                VERSION_MAPPER, stationId, dayKey).stream().findFirst();
    }

    public List<ScheduleVersion> findVersions(long stationId, String dayKey) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + """
                         FROM schedule_version
                         WHERE station_id = ? AND day_key = ?
                         ORDER BY version_no""",
                VERSION_MAPPER, stationId, dayKey);
    }

    /**
     * 以行锁（FOR UPDATE）加载同站同日最新版本，配合同站事务串行化使用。
     */
    public Optional<ScheduleVersion> findLatestVersionForUpdate(long stationId, String dayKey) {
        return jdbc.query("SELECT " + VERSION_COLUMNS + """
                         FROM schedule_version
                         WHERE station_id = ? AND day_key = ?
                         ORDER BY version_no DESC LIMIT 1
                         FOR UPDATE""",
                VERSION_MAPPER, stationId, dayKey).stream().findFirst();
    }

    public long insertVersion(long stationId, String dayKey, int versionNo, Instant now) {
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("station_id", stationId);
        params.put("day_key", dayKey);
        params.put("version_no", versionNo);
        params.put("status", VersionStatus.DRAFT.name());
        params.put("published_at", null);
        params.put("created_at", Timestamp.from(now));
        return versionInsert.executeAndReturnKey(params).longValue();
    }

    public int publishVersion(long versionId, Instant publishedAt) {
        return jdbc.update("""
                        UPDATE schedule_version
                        SET status = ?, published_at = ?
                        WHERE id = ? AND status = ?""",
                VersionStatus.PUBLISHED.name(), Timestamp.from(publishedAt),
                versionId, VersionStatus.DRAFT.name());
    }

    public long insertEntry(ScheduleEntry entry) {
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("request_id", entry.getRequestId());
        params.put("satellite_id", entry.getSatelliteId());
        params.put("station_id", entry.getStationId());
        params.put("window_id", entry.getWindowId());
        params.put("version_id", entry.getVersionId());
        params.put("priority", entry.getPriority());
        params.put("status", entry.getStatus().name());
        params.put("start_time", Timestamp.from(entry.getStartTime()));
        params.put("end_time", Timestamp.from(entry.getEndTime()));
        params.put("preempted_by_entry_id", entry.getPreemptedByEntryId());
        params.put("suggestion_window_id", entry.getSuggestionWindowId());
        params.put("suggestion_start_time",
                entry.getSuggestionStartTime() == null ? null : Timestamp.from(entry.getSuggestionStartTime()));
        params.put("suggestion_end_time",
                entry.getSuggestionEndTime() == null ? null : Timestamp.from(entry.getSuggestionEndTime()));
        params.put("created_at", Timestamp.from(entry.getCreatedAt()));
        params.put("updated_at", Timestamp.from(entry.getUpdatedAt()));
        return entryInsert.executeAndReturnKey(params).longValue();
    }

    public Optional<ScheduleEntry> findEntryById(long entryId) {
        return jdbc.query("SELECT " + ENTRY_COLUMNS + " FROM schedule_entry WHERE id = ?",
                ENTRY_MAPPER, entryId).stream().findFirst();
    }

    public Optional<ScheduleEntry> findEntryByRequestId(String requestId) {
        return jdbc.query("SELECT " + ENTRY_COLUMNS + " FROM schedule_entry WHERE request_id = ?",
                ENTRY_MAPPER, requestId).stream().findFirst();
    }

    /**
     * 按“站＋窗口”锁定最新编排条目（行锁），用于回报与漏报竞争的串行化。
     */
    public Optional<ScheduleEntry> findLatestEntryByWindowForUpdate(long stationId, long windowId) {
        return jdbc.query("SELECT " + ENTRY_COLUMNS + """
                         FROM schedule_entry
                         WHERE station_id = ? AND window_id = ?
                         ORDER BY id DESC LIMIT 1
                         FOR UPDATE""",
                ENTRY_MAPPER, stationId, windowId).stream().findFirst();
    }

    public List<ScheduleEntry> findEntriesByVersion(long versionId) {
        return jdbc.query("SELECT " + ENTRY_COLUMNS + """
                         FROM schedule_entry WHERE version_id = ? ORDER BY start_time""",
                ENTRY_MAPPER, versionId);
    }

    public List<ScheduleEntry> findEntriesByVersionForUpdate(long versionId) {
        return jdbc.query("SELECT " + ENTRY_COLUMNS + """
                         FROM schedule_entry WHERE version_id = ?
                         ORDER BY start_time FOR UPDATE""",
                ENTRY_MAPPER, versionId);
    }

    /**
     * 查询某版本内与给定半开区间内部重叠的已编排条目（行锁）。
     * 半开区间 [s1,e1) 与 [s2,e2) 内部重叠条件：s1 &lt; e2 AND s2 &lt; e1；
     * 端点相接（e1 == s2 或 e2 == s1）不满足该条件，不算冲突。
     */
    public List<ScheduleEntry> findOverlappingScheduledForUpdate(
            long versionId, Instant start, Instant end) {
        return jdbc.query("SELECT " + ENTRY_COLUMNS + """
                         FROM schedule_entry
                         WHERE version_id = ? AND status = ?
                           AND start_time < ? AND ? < end_time
                         ORDER BY start_time
                         FOR UPDATE""",
                ENTRY_MAPPER, versionId, EntryStatus.SCHEDULED.name(),
                Timestamp.from(end), Timestamp.from(start));
    }

    public int markPreempted(long entryId, long preemptedByEntryId,
                             Long suggestionWindowId, Instant suggestionStart,
                             Instant suggestionEnd, Instant now) {
        var params = new java.util.ArrayList<>();
        params.add(EntryStatus.PREEMPTED.name());
        params.add(preemptedByEntryId);
        params.add(suggestionWindowId);
        params.add(suggestionStart == null ? null : Timestamp.from(suggestionStart));
        params.add(suggestionEnd == null ? null : Timestamp.from(suggestionEnd));
        params.add(Timestamp.from(now));
        params.add(entryId);
        params.add(EntryStatus.SCHEDULED.name());
        return jdbc.update("""
                        UPDATE schedule_entry
                        SET status = ?, preempted_by_entry_id = ?,
                            suggestion_window_id = ?, suggestion_start_time = ?,
                            suggestion_end_time = ?, updated_at = ?
                        WHERE id = ? AND status = ?""",
                params.toArray(),
                new int[]{Types.VARCHAR, Types.BIGINT, Types.BIGINT,
                        Types.TIMESTAMP, Types.TIMESTAMP, Types.TIMESTAMP,
                        Types.BIGINT, Types.VARCHAR});
    }

    public int markCancelled(long entryId, Instant now) {
        return jdbc.update("""
                        UPDATE schedule_entry
                        SET status = ?, updated_at = ?
                        WHERE id = ? AND status = ?""",
                EntryStatus.CANCELLED.name(), Timestamp.from(now),
                entryId, EntryStatus.SCHEDULED.name());
    }

    /**
     * 漏报扫描：对已过结束时刻仍处“已编排”的条目条件置为漏报，返回受影响行数。
     */
    public int markMissed(Instant now) {
        return jdbc.update("""
                        UPDATE schedule_entry
                        SET status = ?, updated_at = ?
                        WHERE status = ? AND end_time <= ?""",
                EntryStatus.MISSED.name(), Timestamp.from(now),
                EntryStatus.SCHEDULED.name(), Timestamp.from(now));
    }

    /**
     * 条件 CAS：仅当条目仍为“已编排”时置为漏报，用于漏报与迟到回报的终态竞争。
     */
    public int casMissed(long entryId, Instant now) {
        return jdbc.update("""
                        UPDATE schedule_entry
                        SET status = ?, updated_at = ?
                        WHERE id = ? AND status = ?""",
                EntryStatus.MISSED.name(), Timestamp.from(now),
                entryId, EntryStatus.SCHEDULED.name());
    }

    /**
     * 查询结束时刻已过、仍未回报的已编排条目（行锁），用于漏报扫描。
     */
    public List<ScheduleEntry> findExpiredScheduledForUpdate(Instant now) {
        return jdbc.query("SELECT " + ENTRY_COLUMNS + """
                         FROM schedule_entry
                         WHERE status = ? AND end_time <= ?
                         ORDER BY end_time
                         FOR UPDATE""",
                ENTRY_MAPPER, EntryStatus.SCHEDULED.name(), Timestamp.from(now));
    }
}
