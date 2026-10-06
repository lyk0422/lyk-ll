package com.example.starter.repo;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * 放行与确认类数据访问（吊厢放行、救援分段清空双人确认、复开双岗确认）。
 */
@Repository
public class ConfirmationRepository {

    private final JdbcTemplate jdbc;

    public ConfirmationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录分段吊厢放行；同一事件同一分段重复调用返回 false（已放行）。
     */
    public boolean insertCabinRelease(long eventId, long segmentId, String operatorId) {
        try {
            jdbc.update("INSERT INTO cabin_release (event_id, segment_id, operator_id) VALUES (?, ?, ?)",
                    eventId, segmentId, operatorId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 查询分段放行时间，未放行返回 null。
     */
    public Instant findCabinReleaseAt(long eventId, long segmentId) {
        List<Timestamp> rows = jdbc.query(
                "SELECT created_at FROM cabin_release WHERE event_id = ? AND segment_id = ?",
                (rs, n) -> rs.getTimestamp("created_at"), eventId, segmentId);
        return rows.isEmpty() ? null : rows.get(0).toInstant();
    }

    /**
     * 记录分段清空确认；同一确认人重复确认返回 false（单人确认不重复计数）。
     */
    public boolean insertClearance(long eventId, long segmentId, String confirmerId) {
        try {
            jdbc.update("INSERT INTO segment_clearance (event_id, segment_id, confirmer_id) VALUES (?, ?, ?)",
                    eventId, segmentId, confirmerId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 分段清空确认人列表（去重）。
     */
    public List<String> listConfirmers(long eventId, long segmentId) {
        return jdbc.query(
                "SELECT confirmer_id FROM segment_clearance WHERE event_id = ? AND segment_id = ? ORDER BY id",
                (rs, n) -> rs.getString("confirmer_id"), eventId, segmentId);
    }

    /**
     * 事件中已达到双人确认的分段数。
     */
    public int countClearedSegments(long eventId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT segment_id FROM segment_clearance WHERE event_id = ? "
                        + "GROUP BY segment_id HAVING COUNT(DISTINCT confirmer_id) >= 2) t",
                Integer.class, eventId);
        return count == null ? 0 : count;
    }

    /**
     * 记录复开岗位确认；同一岗位重复确认返回 false。
     */
    public boolean insertReopenConfirmation(long eventId, String role, String operatorId) {
        try {
            jdbc.update("INSERT INTO reopen_confirmation (event_id, role, operator_id) VALUES (?, ?, ?)",
                    eventId, role, operatorId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 事件已确认的复开岗位列表。
     */
    public List<String> listReopenRoles(long eventId) {
        return jdbc.query(
                "SELECT role FROM reopen_confirmation WHERE event_id = ? ORDER BY id",
                (rs, n) -> rs.getString("role"), eventId);
    }
}
