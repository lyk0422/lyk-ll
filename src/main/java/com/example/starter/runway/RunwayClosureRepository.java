package com.example.starter.runway;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import com.example.starter.runway.dto.ClosureEventView;
import com.example.starter.runway.dto.InspectionView;
import com.example.starter.runway.dto.NotamView;
import com.example.starter.runway.dto.TimelineEntryView;

/**
 * 跑道关闭事件数据访问：基于 JdbcTemplate 的参数化 SQL。
 */
@Repository
public class RunwayClosureRepository {

    private final JdbcTemplate jdbc;

    public RunwayClosureRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ClosureEventView> EVENT_MAPPER = (rs, n) -> new ClosureEventView(
            rs.getLong("id"),
            rs.getString("runway_id"),
            rs.getString("event_type"),
            rs.getString("status"),
            rs.getInt("escalated") == 1,
            rs.getInt("repair_required") == 1,
            rs.getInt("repair_cleared") == 1,
            rs.getTimestamp("reported_at").toInstant(),
            rs.getTimestamp("closed_at").toInstant(),
            toInstant(rs.getTimestamp("reopened_at")),
            toInstant(rs.getTimestamp("archived_at")));

    private static final RowMapper<InspectionView> INSPECTION_MAPPER = (rs, n) -> new InspectionView(
            rs.getLong("id"),
            rs.getLong("closure_event_id"),
            rs.getInt("inspector_count"),
            rs.getInt("rvr_meters"),
            rs.getBigDecimal("friction_coefficient"),
            rs.getString("assessment"),
            rs.getInt("effective") == 1,
            rs.getInt("late") == 1,
            rs.getInt("delayed") == 1,
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("recorded_at").toInstant());

    private static final RowMapper<NotamView> NOTAM_MAPPER = (rs, n) -> new NotamView(
            rs.getLong("id"),
            rs.getLong("closure_event_id"),
            rs.getString("notam_type"),
            rs.getString("content"),
            rs.getTimestamp("published_at").toInstant());

    private static final RowMapper<TimelineEntryView> TIMELINE_MAPPER = (rs, n) -> new TimelineEntryView(
            rs.getString("from_status"),
            rs.getString("to_status"),
            rs.getString("reason"),
            rs.getTimestamp("occurred_at").toInstant());

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 插入关闭事件；active_key 唯一约束冲突时抛出 DuplicateKeyException。
     */
    public long insertEvent(String runwayId, String eventType, String requestId,
            Instant reportedAt, Instant closedAt, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO runway_closure_event (runway_id, event_type, status, escalated,"
                            + " repair_required, repair_cleared, reported_at, closed_at, active_key,"
                            + " request_id, created_at, updated_at)"
                            + " VALUES (?, ?, 'TEMP_CLOSED', 0, 0, 0, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, runwayId);
            ps.setString(2, eventType);
            ps.setTimestamp(3, Timestamp.from(reportedAt));
            ps.setTimestamp(4, Timestamp.from(closedAt));
            ps.setString(5, runwayId);
            ps.setString(6, requestId);
            ps.setTimestamp(7, Timestamp.from(now));
            ps.setTimestamp(8, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<ClosureEventView> findEventById(long id) {
        List<ClosureEventView> rows = jdbc.query(
                "SELECT * FROM runway_closure_event WHERE id = ?", EVENT_MAPPER, id);
        return rows.stream().findFirst();
    }

    public void updateEventStatus(long id, String status, Instant reopenedAt, Instant archivedAt, Instant now) {
        jdbc.update("UPDATE runway_closure_event SET status = ?,"
                        + " reopened_at = COALESCE(?, reopened_at),"
                        + " archived_at = COALESCE(?, archived_at),"
                        + " active_key = CASE WHEN ? IN ('REOPENED', 'ARCHIVED') THEN NULL ELSE active_key END,"
                        + " updated_at = ? WHERE id = ?",
                status,
                reopenedAt == null ? null : Timestamp.from(reopenedAt),
                archivedAt == null ? null : Timestamp.from(archivedAt),
                status,
                Timestamp.from(now), id);
    }

    public void updateEventFlags(long id, boolean escalated, boolean repairRequired, boolean repairCleared, Instant now) {
        jdbc.update("UPDATE runway_closure_event SET escalated = ?, repair_required = ?, repair_cleared = ?,"
                        + " updated_at = ? WHERE id = ?",
                escalated ? 1 : 0, repairRequired ? 1 : 0, repairCleared ? 1 : 0,
                Timestamp.from(now), id);
    }

    public long insertInspection(long eventId, String requestId, int inspectorCount, int rvrMeters,
            BigDecimal friction, String assessment, boolean effective, boolean late, boolean delayed,
            Instant startedAt, Instant recordedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO inspection_record (closure_event_id, request_id, inspector_count, rvr_meters,"
                            + " friction_coefficient, assessment, effective, late, delayed, started_at, recorded_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, eventId);
            ps.setString(2, requestId);
            ps.setInt(3, inspectorCount);
            ps.setInt(4, rvrMeters);
            ps.setBigDecimal(5, friction);
            ps.setString(6, assessment);
            ps.setInt(7, effective ? 1 : 0);
            ps.setInt(8, late ? 1 : 0);
            ps.setInt(9, delayed ? 1 : 0);
            ps.setTimestamp(10, Timestamp.from(startedAt));
            ps.setTimestamp(11, Timestamp.from(recordedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<InspectionView> findInspectionsByEvent(long eventId) {
        return jdbc.query("SELECT * FROM inspection_record WHERE closure_event_id = ?"
                + " ORDER BY started_at, id", INSPECTION_MAPPER, eventId);
    }

    public long insertNotam(long eventId, String notamType, String content, String requestId, Instant publishedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO notam_record (closure_event_id, notam_type, content, request_id, published_at)"
                            + " VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, eventId);
            ps.setString(2, notamType);
            ps.setString(3, content);
            ps.setString(4, requestId);
            ps.setTimestamp(5, Timestamp.from(publishedAt));
            return ps;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }

    public List<NotamView> findNotamsByEvent(long eventId) {
        return jdbc.query("SELECT * FROM notam_record WHERE closure_event_id = ? ORDER BY id",
                NOTAM_MAPPER, eventId);
    }

    public void insertTimeline(long eventId, String fromStatus, String toStatus, String reason, Instant occurredAt) {
        jdbc.update("INSERT INTO closure_status_timeline (closure_event_id, from_status, to_status, reason, occurred_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                eventId, fromStatus, toStatus, reason, Timestamp.from(occurredAt));
    }

    public List<TimelineEntryView> findTimelineByEvent(long eventId) {
        return jdbc.query("SELECT * FROM closure_status_timeline WHERE closure_event_id = ? ORDER BY id",
                TIMELINE_MAPPER, eventId);
    }

    /**
     * 幂等记录行。
     */
    public record IdempotencyRow(String fingerprint, String responseBody) {
    }

    public Optional<IdempotencyRow> findIdempotency(String operation, String requestId) {
        List<IdempotencyRow> rows = jdbc.query(
                "SELECT fingerprint, response_body FROM idempotency_record WHERE operation = ? AND request_id = ?",
                (rs, n) -> new IdempotencyRow(rs.getString("fingerprint"), rs.getString("response_body")),
                operation, requestId);
        return rows.stream().findFirst();
    }

    public void insertIdempotency(String operation, String requestId, String fingerprint,
            String responseBody, Instant now) {
        jdbc.update("INSERT INTO idempotency_record (operation, request_id, fingerprint, response_body, created_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                operation, requestId, fingerprint, responseBody, Timestamp.from(now));
    }
}
