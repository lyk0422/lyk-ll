package com.example.starter.schedule;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 编排相关数据访问：卫星、地面站、过境窗口、编排申请幂等记录、编排条目、重排建议与发布版本。
 * 时间列一律按 UTC 的 LocalDateTime 读写，与 JVM 时区无关。
 */
@Repository
public class ScheduleRepository {

    /**
     * 过境窗口行。
     */
    public record WindowRow(long id, String windowCode, long satelliteId, String satelliteCode,
                            long stationId, String stationCode, LocalDateTime startUtc,
                            LocalDateTime endUtc, LocalDate day) {
    }

    /**
     * 编排条目行。
     */
    public record EntryRow(long id, String requestId, long satelliteId, String satelliteCode,
                           long stationId, String stationCode, long windowId, String windowCode,
                           LocalDateTime startUtc, LocalDateTime endUtc, int priority,
                           LocalDate day, String status, Integer publishedVersion,
                           String preemptedByRequestId) {
    }

    /**
     * 幂等申请记录行。
     */
    public record RequestRow(String requestId, String fingerprint, int httpStatus, String responseBody) {
    }

    private static final RowMapper<EntryRow> ENTRY_MAPPER = (rs, n) -> new EntryRow(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getLong("satellite_id"),
            rs.getString("satellite_code"),
            rs.getLong("station_id"),
            rs.getString("station_code"),
            rs.getLong("window_id"),
            rs.getString("window_code"),
            rs.getObject("start_utc", LocalDateTime.class),
            rs.getObject("end_utc", LocalDateTime.class),
            rs.getInt("priority"),
            rs.getObject("schedule_day", LocalDate.class),
            rs.getString("status"),
            rs.getObject("published_version", Integer.class),
            rs.getString("preempted_by_request_id"));

    private static final String ENTRY_SELECT =
            "SELECT e.id, e.request_id, e.satellite_id, s.code AS satellite_code, "
                    + "e.station_id, g.code AS station_code, e.window_id, w.window_code, "
                    + "w.start_utc, w.end_utc, e.priority, e.schedule_day, e.status, "
                    + "e.published_version, e.preempted_by_request_id "
                    + "FROM schedule_entry e "
                    + "JOIN satellite s ON s.id = e.satellite_id "
                    + "JOIN ground_station g ON g.id = e.station_id "
                    + "JOIN pass_window w ON w.id = e.window_id ";

    private final JdbcTemplate jdbc;

    public ScheduleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findSatelliteId(String code) {
        List<Long> rows = jdbc.query("SELECT id FROM satellite WHERE code = ?",
                (rs, n) -> rs.getLong(1), code);
        return rows.stream().findFirst();
    }

    public Optional<Long> findStationId(String code) {
        List<Long> rows = jdbc.query("SELECT id FROM ground_station WHERE code = ?",
                (rs, n) -> rs.getLong(1), code);
        return rows.stream().findFirst();
    }

    public Optional<WindowRow> findWindow(String windowCode) {
        List<WindowRow> rows = jdbc.query(
                "SELECT w.id, w.window_code, w.satellite_id, s.code AS satellite_code, "
                        + "w.station_id, g.code AS station_code, w.start_utc, w.end_utc "
                        + "FROM pass_window w "
                        + "JOIN satellite s ON s.id = w.satellite_id "
                        + "JOIN ground_station g ON g.id = w.station_id "
                        + "WHERE w.window_code = ?",
                (rs, n) -> {
                    LocalDateTime start = rs.getObject("start_utc", LocalDateTime.class);
                    return new WindowRow(
                            rs.getLong("id"),
                            rs.getString("window_code"),
                            rs.getLong("satellite_id"),
                            rs.getString("satellite_code"),
                            rs.getLong("station_id"),
                            rs.getString("station_code"),
                            start,
                            rs.getObject("end_utc", LocalDateTime.class),
                            start.toLocalDate());
                }, windowCode);
        return rows.stream().findFirst();
    }

    public Optional<RequestRow> findRequest(String requestId) {
        List<RequestRow> rows = jdbc.query(
                "SELECT request_id, fingerprint, http_status, response_body "
                        + "FROM schedule_request WHERE request_id = ?",
                (rs, n) -> new RequestRow(
                        rs.getString("request_id"),
                        rs.getString("fingerprint"),
                        rs.getInt("http_status"),
                        rs.getString("response_body")),
                requestId);
        return rows.stream().findFirst();
    }

    public void insertRequest(String requestId, String fingerprint, long satelliteId, long stationId,
                              long windowId, int priority, int httpStatus, String responseBody) {
        jdbc.update("INSERT INTO schedule_request "
                        + "(request_id, fingerprint, satellite_id, station_id, window_id, priority, "
                        + "http_status, response_body, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                requestId, fingerprint, satelliteId, stationId, windowId, priority,
                httpStatus, responseBody, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 查询同站与目标窗口内部重叠的生效条目：半开区间重叠条件为 existing.start &lt; end 且 existing.end &gt; start，
     * 端点相接（existing.end = start 或 existing.start = end）不算冲突。
     */
    public List<EntryRow> findConflictingEntries(long stationId, LocalDateTime startUtc, LocalDateTime endUtc) {
        return jdbc.query(ENTRY_SELECT
                        + "WHERE e.station_id = ? AND e.status = 'SCHEDULED' "
                        + "AND w.start_utc < ? AND w.end_utc > ?",
                ENTRY_MAPPER, stationId, endUtc, startUtc);
    }

    public long insertEntry(String requestId, long satelliteId, long stationId, long windowId,
                            int priority, LocalDate day) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        String activeSlot = stationId + ":" + windowId;
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO schedule_entry "
                            + "(request_id, satellite_id, station_id, window_id, priority, schedule_day, status, "
                            + "published_version, preempted_by_request_id, active_slot, created_at) "
                            + "VALUES (?,?,?,?,?,?,'SCHEDULED',NULL,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setLong(2, satelliteId);
            ps.setLong(3, stationId);
            ps.setLong(4, windowId);
            ps.setInt(5, priority);
            ps.setObject(6, day);
            ps.setNull(7, java.sql.Types.VARCHAR);
            ps.setString(8, activeSlot);
            ps.setObject(9, LocalDateTime.now(ZoneOffset.UTC));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未获取到编排条目主键");
        }
        return key.longValue();
    }

    public void markPreempted(long entryId, String preemptedByRequestId) {
        jdbc.update("UPDATE schedule_entry SET status = 'PREEMPTED', preempted_by_request_id = ?, "
                + "active_slot = NULL WHERE id = ?", preemptedByRequestId, entryId);
    }

    public void insertSuggestion(long entryId, long satelliteId, long stationId, String suggestedWindowCodes) {
        jdbc.update("INSERT INTO replan_suggestion "
                        + "(entry_id, satellite_id, station_id, suggested_window_codes, created_at) "
                        + "VALUES (?,?,?,?,?)",
                entryId, satelliteId, stationId, suggestedWindowCodes, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 重排建议候选：同星同站、开始时刻晚于指定时刻、且与同站生效条目不冲突的窗口，按开始时刻升序取前 3。
     */
    public List<String> findReplanCandidates(long satelliteId, long stationId, LocalDateTime afterUtc,
                                             long excludeWindowId) {
        return jdbc.query(
                "SELECT w.window_code FROM pass_window w "
                        + "WHERE w.satellite_id = ? AND w.station_id = ? AND w.start_utc >= ? AND w.id <> ? "
                        + "AND NOT EXISTS ("
                        + "  SELECT 1 FROM schedule_entry e JOIN pass_window ew ON ew.id = e.window_id "
                        + "  WHERE e.station_id = ? AND e.status = 'SCHEDULED' "
                        + "  AND ew.start_utc < w.end_utc AND ew.end_utc > w.start_utc) "
                        + "ORDER BY w.start_utc LIMIT 3",
                (rs, n) -> rs.getString(1), satelliteId, stationId, afterUtc, excludeWindowId, stationId);
    }

    public boolean existsPublication(LocalDate day) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM publication WHERE schedule_day = ?",
                Integer.class, day);
        return count != null && count > 0;
    }

    public Optional<Integer> maxVersion(LocalDate day) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) FROM publication WHERE schedule_day = ?",
                Integer.class, day);
        return max == null || max == 0 ? Optional.empty() : Optional.of(max);
    }

    public void insertPublication(LocalDate day, int version, int entryCount) {
        jdbc.update("INSERT INTO publication (schedule_day, version, entry_count, published_at) VALUES (?,?,?,?)",
                day, version, entryCount, LocalDateTime.now(ZoneOffset.UTC));
    }

    public List<EntryRow> findUnpublishedEntries(LocalDate day) {
        return jdbc.query(ENTRY_SELECT
                        + "WHERE e.schedule_day = ? AND e.status = 'SCHEDULED' AND e.published_version IS NULL",
                ENTRY_MAPPER, day);
    }

    public void assignVersion(LocalDate day, int version) {
        jdbc.update("UPDATE schedule_entry SET published_version = ? "
                + "WHERE schedule_day = ? AND status = 'SCHEDULED' AND published_version IS NULL", version, day);
    }

    public List<EntryRow> findEntriesByDay(LocalDate day) {
        return jdbc.query(ENTRY_SELECT + "WHERE e.schedule_day = ? ORDER BY w.start_utc, e.id", ENTRY_MAPPER, day);
    }

    public List<EntryRow> findSnapshot(LocalDate day, int version) {
        return jdbc.query(ENTRY_SELECT
                        + "WHERE e.schedule_day = ? AND e.published_version IS NOT NULL AND e.published_version <= ? "
                        + "ORDER BY w.start_utc, e.id",
                ENTRY_MAPPER, day, version);
    }

    public List<PublicationRow> listPublicationRows(LocalDate day) {
        return jdbc.query("SELECT version, entry_count, published_at FROM publication WHERE schedule_day = ? "
                        + "ORDER BY version",
                (rs, n) -> new PublicationRow(rs.getInt("version"), rs.getInt("entry_count"),
                        rs.getObject("published_at", LocalDateTime.class)), day);
    }

    /**
     * 发布版本行。
     */
    public record PublicationRow(int version, int entryCount, LocalDateTime publishedAt) {
    }

    public Optional<EntryRow> findEntry(long entryId) {
        List<EntryRow> rows = jdbc.query(ENTRY_SELECT + "WHERE e.id = ?", ENTRY_MAPPER, entryId);
        return rows.stream().findFirst();
    }

    public void markCancelled(long entryId) {
        jdbc.update("UPDATE schedule_entry SET status = 'CANCELLED', active_slot = NULL WHERE id = ?", entryId);
    }
}
