package com.example.starter.report;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 执行回报与漏报数据访问：窗口终态（每窗口每站唯一一行）与迟到回报事件。
 * 时间列一律按 UTC 的 LocalDateTime 读写。
 */
@Repository
public class ReportRepository {

    /**
     * 窗口终态行。
     */
    public record OutcomeRow(long windowId, long stationId, String state, String reportStatus) {
    }

    private final JdbcTemplate jdbc;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按窗口编码查询窗口，返回 [windowId, stationId]。
     */
    public Optional<long[]> findWindow(String windowCode) {
        List<long[]> rows = jdbc.query("SELECT id, station_id FROM pass_window WHERE window_code = ?",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}, windowCode);
        return rows.stream().findFirst();
    }

    public Optional<Long> findStationId(String stationCode) {
        List<Long> rows = jdbc.query("SELECT id FROM ground_station WHERE code = ?",
                (rs, n) -> rs.getLong(1), stationCode);
        return rows.stream().findFirst();
    }

    public Optional<OutcomeRow> findOutcome(long windowId, long stationId) {
        List<OutcomeRow> rows = jdbc.query(
                "SELECT window_id, station_id, state, report_status FROM window_outcome "
                        + "WHERE window_id = ? AND station_id = ?",
                (rs, n) -> new OutcomeRow(rs.getLong(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4)),
                windowId, stationId);
        return rows.stream().findFirst();
    }

    public void insertReported(long windowId, long stationId, String status) {
        jdbc.update("INSERT INTO window_outcome "
                        + "(window_id, station_id, state, report_status, reported_at, missed_at) "
                        + "VALUES (?,?,'REPORTED',?,?,NULL)",
                windowId, stationId, status, LocalDateTime.now(ZoneOffset.UTC));
    }

    public void insertMissed(long windowId, long stationId) {
        jdbc.update("INSERT INTO window_outcome "
                        + "(window_id, station_id, state, report_status, reported_at, missed_at) "
                        + "VALUES (?,?,'MISSED',NULL,NULL,?)",
                windowId, stationId, LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 记录迟到回报事件；同一窗口同一站只记一次，重复迟到回报幂等返回 false。
     */
    public boolean insertLateEvent(long windowId, long stationId, String status) {
        try {
            jdbc.update("INSERT INTO late_report_event (window_id, station_id, report_status, reported_at) "
                            + "VALUES (?,?,?,?)",
                    windowId, stationId, status, LocalDateTime.now(ZoneOffset.UTC));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 查找结束时刻早于 now、仍处于已编排状态且尚无终态的窗口，返回 [windowId, stationId]。
     */
    public List<long[]> findUnreportedActiveWindows(LocalDateTime now) {
        return jdbc.query(
                "SELECT e.window_id, e.station_id FROM schedule_entry e "
                        + "JOIN pass_window w ON w.id = e.window_id "
                        + "WHERE e.status = 'SCHEDULED' AND w.end_utc < ? "
                        + "AND NOT EXISTS (SELECT 1 FROM window_outcome o "
                        + "  WHERE o.window_id = e.window_id AND o.station_id = e.station_id)",
                (rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}, now);
    }

    public String windowCode(long windowId) {
        return jdbc.queryForObject("SELECT window_code FROM pass_window WHERE id = ?",
                String.class, windowId);
    }
}
