package com.example.starter.rescue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 救援案件与时间线数据访问：时间戳以 UTC 本地时间存取，不受 JVM 默认时区影响。
 */
@Repository
public class RescueRepository {

    private final JdbcTemplate jdbc;

    public RescueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Instant → UTC 本地时间。 */
    private static LocalDateTime utc(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime ldt) {
        return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
    }

    private static RescueCase map(ResultSet rs, int rowNum) throws SQLException {
        return new RescueCase(
                rs.getLong("id"),
                rs.getString("request_id"),
                rs.getString("report_fingerprint"),
                rs.getLong("elevator_id"),
                rs.getString("elevator_code"),
                rs.getString("status"),
                toInstant(rs.getObject("reported_at", LocalDateTime.class)),
                rs.getString("dispatch_request_id"),
                rs.getString("dispatch_fingerprint"),
                toInstant(rs.getObject("dispatched_at", LocalDateTime.class)),
                rs.getString("dispatcher"),
                toInstant(rs.getObject("arrived_at", LocalDateTime.class)),
                rs.getInt("escalated") != 0,
                toInstant(rs.getObject("escalated_at", LocalDateTime.class)),
                toInstant(rs.getObject("rescued_at", LocalDateTime.class)),
                rs.getObject("rescuer1_id") == null ? null : rs.getLong("rescuer1_id"),
                rs.getObject("rescuer2_id") == null ? null : rs.getLong("rescuer2_id"),
                toInstant(rs.getObject("rechecked_at", LocalDateTime.class)),
                rs.getString("recheck_result"),
                rs.getString("close_request_id"),
                rs.getString("close_fingerprint"),
                toInstant(rs.getObject("closed_at", LocalDateTime.class)),
                rs.getLong("version"));
    }

    /**
     * 按 ID 查询。
     */
    public Optional<RescueCase> findById(long id) {
        return jdbc.query("SELECT * FROM rescue_case WHERE id = ?", RescueRepository::map, id)
                .stream().findFirst();
    }

    /**
     * 按 ID 加行锁查询，用于状态流转串行化。
     */
    public Optional<RescueCase> findByIdForUpdate(long id) {
        return jdbc.query("SELECT * FROM rescue_case WHERE id = ? FOR UPDATE", RescueRepository::map, id)
                .stream().findFirst();
    }

    /**
     * 按接报幂等键查询。
     */
    public Optional<RescueCase> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM rescue_case WHERE request_id = ?", RescueRepository::map, requestId)
                .stream().findFirst();
    }

    /**
     * 查询电梯当前进行中的救援单（active_key 唯一约束保证至多一条）。
     */
    public Optional<RescueCase> findActiveByElevator(long elevatorId, String elevatorCode) {
        return jdbc.query("SELECT * FROM rescue_case WHERE elevator_id = ? AND active_key = ?",
                        RescueRepository::map, elevatorId, elevatorCode)
                .stream().findFirst();
    }

    /**
     * 统计电梯在指定 UTC 时刻起（含）之后的困人（接报）次数，用于 90 日窗口判定。
     */
    public int countReportedSince(long elevatorId, Instant windowStartInclusive) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rescue_case WHERE elevator_id = ? AND reported_at >= ?",
                Integer.class, elevatorId, utc(windowStartInclusive));
        return count == null ? 0 : count;
    }

    /**
     * 查询待升级案件：接报后未到场且未升级，由调用方按 30 分钟时限过滤。
     */
    public List<RescueCase> findPendingArrival() {
        return jdbc.query("SELECT * FROM rescue_case WHERE status IN ('RECEIVED','DISPATCHED') AND escalated = 0",
                RescueRepository::map);
    }

    /**
     * 新建案件（接报），状态 RECEIVED，active_key=电梯编号。
     */
    public long insert(String requestId, String fingerprint, long elevatorId, String elevatorCode,
                       Instant reportedAt) {
        jdbc.update("INSERT INTO rescue_case (request_id, report_fingerprint, elevator_id, elevator_code,"
                        + " status, active_key, reported_at, escalated, version)"
                        + " VALUES (?, ?, ?, ?, 'RECEIVED', ?, ?, 0, 0)",
                requestId, fingerprint, elevatorId, elevatorCode, elevatorCode, utc(reportedAt));
        Long id = jdbc.queryForObject("SELECT id FROM rescue_case WHERE request_id = ?", Long.class, requestId);
        return id;
    }

    /**
     * 派单：写入派单幂等键、指纹、时间与派单人，状态置 DISPATCHED。
     */
    public void markDispatched(long id, String dispatchRequestId, String dispatchFingerprint,
                               String dispatcher, Instant dispatchedAt) {
        jdbc.update("UPDATE rescue_case SET status = 'DISPATCHED', dispatch_request_id = ?,"
                        + " dispatch_fingerprint = ?, dispatcher = ?, dispatched_at = ?, version = version + 1"
                        + " WHERE id = ?",
                dispatchRequestId, dispatchFingerprint, dispatcher, utc(dispatchedAt), id);
    }

    /**
     * 到场。
     */
    public void markArrived(long id, Instant arrivedAt) {
        jdbc.update("UPDATE rescue_case SET status = 'ARRIVED', arrived_at = ?, version = version + 1 WHERE id = ?",
                utc(arrivedAt), id);
    }

    /**
     * 超时升级通报。
     */
    public void markEscalated(long id, Instant escalatedAt) {
        jdbc.update("UPDATE rescue_case SET escalated = 1, escalated_at = ?, version = version + 1 WHERE id = ?",
                utc(escalatedAt), id);
    }

    /**
     * 施救完成：须两名持证人员。
     */
    public void markRescued(long id, long rescuer1Id, long rescuer2Id, Instant rescuedAt) {
        jdbc.update("UPDATE rescue_case SET status = 'RESCUING', rescued_at = ?, rescuer1_id = ?, rescuer2_id = ?,"
                        + " version = version + 1 WHERE id = ?",
                utc(rescuedAt), rescuer1Id, rescuer2Id, id);
    }

    /**
     * 复检合格（与电梯复梯同事务）。
     */
    public void markRechecked(long id, String result, Instant recheckedAt) {
        jdbc.update("UPDATE rescue_case SET status = 'RECHECKED', recheck_result = ?, rechecked_at = ?,"
                        + " version = version + 1 WHERE id = ?",
                result, utc(recheckedAt), id);
    }

    /**
     * 回访结案：终态，active_key 置 NULL 释放电梯进行中占用。
     */
    public void markClosed(long id, String closeRequestId, String closeFingerprint, Instant closedAt) {
        jdbc.update("UPDATE rescue_case SET status = 'CLOSED', close_request_id = ?, close_fingerprint = ?,"
                        + " closed_at = ?, active_key = NULL, version = version + 1 WHERE id = ?",
                closeRequestId, closeFingerprint, utc(closedAt), id);
    }

    /**
     * 追加时间线事件。
     */
    public void insertEvent(long caseId, long elevatorId, String eventType, String detail, Instant occurredAt) {
        jdbc.update("INSERT INTO rescue_event (case_id, elevator_id, event_type, detail, occurred_at)"
                        + " VALUES (?, ?, ?, ?, ?)",
                caseId, elevatorId, eventType, detail, utc(occurredAt));
    }

    /**
     * 时间线事件记录。
     */
    public record CaseEvent(long id, long caseId, long elevatorId, String eventType, String detail,
                            Instant occurredAt) {
    }

    /**
     * 查询案件时间线（按发生时间升序，可复算流转）。
     */
    public List<CaseEvent> listEvents(long caseId) {
        return jdbc.query("SELECT * FROM rescue_event WHERE case_id = ? ORDER BY occurred_at, id",
                (rs, rowNum) -> new CaseEvent(
                        rs.getLong("id"),
                        rs.getLong("case_id"),
                        rs.getLong("elevator_id"),
                        rs.getString("event_type"),
                        rs.getString("detail"),
                        toInstant(rs.getObject("occurred_at", LocalDateTime.class))),
                caseId);
    }
}
