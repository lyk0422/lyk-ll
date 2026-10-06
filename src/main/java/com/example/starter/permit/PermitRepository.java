package com.example.starter.permit;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 作业许可、路段占用与作业回传数据访问。
 */
@Repository
public class PermitRepository {

    private final JdbcClient jdbc;

    public PermitRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 许可行。status：EXECUTING 执行中 / COMPLETED 已完成。 */
    public record PermitRow(long id, String permitNo, long taskId, long segmentId, String workType,
            String status, LocalDateTime issuedAt, LocalDate deadlineDate, LocalDateTime completedAt) {
    }

    public long insertPermit(String permitNo, long taskId, long segmentId, String workType,
            LocalDateTime issuedAt, LocalDate deadlineDate) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO work_permit(permit_no, task_id, segment_id, work_type, status, issued_at, deadline_date)"
                        + " VALUES (?, ?, ?, ?, 'EXECUTING', ?, ?)")
                .params(permitNo, taskId, segmentId, workType, issuedAt, deadlineDate)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<PermitRow> findById(long id) {
        return jdbc.sql("SELECT * FROM work_permit WHERE id = ?")
                .param(id)
                .query(this::mapRow)
                .optional();
    }

    /** 行级锁读取，用于回传串行化。 */
    public Optional<PermitRow> findByIdForUpdate(long id) {
        return jdbc.sql("SELECT * FROM work_permit WHERE id = ? FOR UPDATE")
                .param(id)
                .query(this::mapRow)
                .optional();
    }

    public void markCompleted(long id, LocalDateTime completedAt) {
        jdbc.sql("UPDATE work_permit SET status = 'COMPLETED', completed_at = ? WHERE id = ?")
                .params(completedAt, id)
                .update();
    }

    /** 占用路段执行中许可槽位；主键冲突即同路段已有执行中许可。 */
    public void occupySegment(long segmentId, long permitId) {
        jdbc.sql("INSERT INTO segment_active_permit(segment_id, permit_id) VALUES (?, ?)")
                .params(segmentId, permitId)
                .update();
    }

    public boolean segmentOccupied(long segmentId) {
        return jdbc.sql("SELECT COUNT(*) FROM segment_active_permit WHERE segment_id = ?")
                .param(segmentId)
                .query(Long.class)
                .single() > 0;
    }

    public void releaseSegment(long segmentId) {
        jdbc.sql("DELETE FROM segment_active_permit WHERE segment_id = ?")
                .param(segmentId)
                .update();
    }

    /** 写入回传；permit_id 唯一约束保证一张许可仅一条回传。 */
    public long insertReport(long permitId, String beforePhotoId, String afterPhotoId,
            String disposalRecord, LocalDateTime reportedAt) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO operation_report(permit_id, before_photo_id, after_photo_id, disposal_record, reported_at)"
                        + " VALUES (?, ?, ?, ?, ?)")
                .params(permitId, beforePhotoId, afterPhotoId, disposalRecord, reportedAt)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    private PermitRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PermitRow(rs.getLong("id"), rs.getString("permit_no"), rs.getLong("task_id"),
                rs.getLong("segment_id"), rs.getString("work_type"), rs.getString("status"),
                rs.getObject("issued_at", LocalDateTime.class),
                rs.getObject("deadline_date", LocalDate.class),
                rs.getObject("completed_at", LocalDateTime.class));
    }
}
