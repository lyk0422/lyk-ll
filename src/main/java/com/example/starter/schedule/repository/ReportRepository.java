package com.example.starter.schedule.repository;

import com.example.starter.schedule.domain.ContactEvent;
import com.example.starter.schedule.domain.ContactReport;
import com.example.starter.schedule.model.EventType;
import com.example.starter.schedule.model.ReportStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 执行回报与回报事件流水访问。
 * (station_id, window_id) 唯一约束保证“窗口＋站”去重。
 */
@Repository
public class ReportRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert reportInsert;
    private final SimpleJdbcInsert eventInsert;

    public ReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.reportInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("contact_report")
                .usingColumns("station_id", "window_id", "entry_id", "status", "remark", "reported_at")
                .usingGeneratedKeyColumns("id");
        this.eventInsert = new SimpleJdbcInsert(jdbc)
                .withTableName("contact_report_event")
                .usingColumns("station_id", "window_id", "event_type", "payload", "created_at")
                .usingGeneratedKeyColumns("id");
    }

    private static final RowMapper<ContactReport> REPORT_MAPPER = (rs, n) -> {
        ContactReport r = new ContactReport();
        r.setId(rs.getLong("id"));
        r.setStationId(rs.getLong("station_id"));
        r.setWindowId(rs.getLong("window_id"));
        r.setEntryId(rs.getLong("entry_id"));
        r.setStatus(ReportStatus.valueOf(rs.getString("status")));
        r.setRemark(rs.getString("remark"));
        r.setReportedAt(rs.getTimestamp("reported_at").toInstant());
        r.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return r;
    };

    public Optional<ContactReport> findByWindowForUpdate(long stationId, long windowId) {
        return jdbc.query("""
                         SELECT id, station_id, window_id, entry_id, status, remark,
                                reported_at, created_at
                         FROM contact_report
                         WHERE station_id = ? AND window_id = ?
                         FOR UPDATE""",
                REPORT_MAPPER, stationId, windowId).stream().findFirst();
    }

    /**
     * 尝试插入回报；并发重复回报由唯一索引拒绝并抛 {@link DuplicateKeyException}。
     */
    public long tryInsert(ContactReport report) throws DuplicateKeyException {
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("station_id", report.getStationId());
        params.put("window_id", report.getWindowId());
        params.put("entry_id", report.getEntryId());
        params.put("status", report.getStatus().name());
        params.put("remark", report.getRemark());
        params.put("reported_at", Timestamp.from(report.getReportedAt()));
        return reportInsert.executeAndReturnKey(params).longValue();
    }

    public long insertEvent(long stationId, long windowId, EventType type,
                            String payload, Instant createdAt) {
        var params = new java.util.LinkedHashMap<String, Object>();
        params.put("station_id", stationId);
        params.put("window_id", windowId);
        params.put("event_type", type.name());
        params.put("payload", payload);
        params.put("created_at", Timestamp.from(createdAt));
        return eventInsert.executeAndReturnKey(params).longValue();
    }

    public int countEvents(long stationId, long windowId, EventType type) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contact_report_event WHERE station_id = ? AND window_id = ? AND event_type = ?",
                Integer.class, stationId, windowId, type.name());
        return count == null ? 0 : count;
    }

    public int countReports(long stationId, long windowId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contact_report WHERE station_id = ? AND window_id = ?",
                Integer.class, stationId, windowId);
        return count == null ? 0 : count;
    }
}
