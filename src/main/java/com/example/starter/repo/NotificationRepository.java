package com.example.starter.repo;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 合成通知与改派记录数据访问；改派与通知在同一事务内生成，可按日结日期复算。
 */
@Repository
public class NotificationRepository {

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 通知记录。
     */
    public record NotificationRow(long id, String noticeType, long studentId, Long routeId,
                                  LocalDate noticeDate, String content) {
    }

    /**
     * 改派记录。
     */
    public record ReassignmentRow(long id, long studentId, long fromRouteId, Long toRouteId,
                                  String reason, LocalDate settleDate) {
    }

    /**
     * 写入合成通知；同类型+学生+线路+日期重复时抛出 DuplicateKeyException。
     */
    public long insertNotification(String noticeType, long studentId, Long routeId,
                                   LocalDate noticeDate, String content) {
        jdbc.sql("INSERT INTO notification (notice_type, student_id, route_id, notice_date, content)"
                        + " VALUES (?, ?, ?, ?, ?)")
                .params(noticeType, studentId, routeId, noticeDate, content)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM notification WHERE notice_type = ? AND student_id = ?"
                        + " AND notice_date = ?")
                .params(noticeType, studentId, noticeDate)
                .query(Long.class)
                .single();
    }

    /**
     * 是否已存在同键通知（漏乘名单重复生成时去重）。
     */
    public boolean notificationExists(String noticeType, long studentId, Long routeId,
                                      LocalDate noticeDate) {
        return jdbc.sql("SELECT COUNT(*) FROM notification WHERE notice_type = ? AND student_id = ?"
                        + " AND route_id IS NOT DISTINCT FROM ? AND notice_date = ?")
                .params(noticeType, studentId, routeId, noticeDate)
                .query(Long.class)
                .single() > 0;
    }

    /**
     * 写入改派记录；同学生+原线路+原因+日结日期重复时抛出 DuplicateKeyException。
     */
    public long insertReassignment(long studentId, long fromRouteId, Long toRouteId, String reason,
                                   LocalDate settleDate) {
        jdbc.sql("INSERT INTO reassignment (student_id, from_route_id, to_route_id, reason, settle_date)"
                        + " VALUES (?, ?, ?, ?, ?)")
                .params(studentId, fromRouteId, toRouteId, reason, settleDate)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM reassignment WHERE student_id = ? AND from_route_id = ?"
                        + " AND settle_date = ?")
                .params(studentId, fromRouteId, settleDate)
                .query(Long.class)
                .single();
    }

    /**
     * 按日结日期复算改派记录。
     */
    public List<ReassignmentRow> findReassignmentsBySettleDate(LocalDate settleDate) {
        return jdbc.sql("SELECT id, student_id, from_route_id, to_route_id, reason, settle_date"
                        + " FROM reassignment WHERE settle_date = ? ORDER BY id")
                .param(settleDate)
                .query((rs, n) -> new ReassignmentRow(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        (Long) rs.getObject(4), rs.getString(5), rs.getDate(6).toLocalDate()))
                .list();
    }

    /**
     * 指定日期某类型通知数（日结复算用）。
     */
    public long countNotifications(String noticeType, LocalDate noticeDate) {
        return jdbc.sql("SELECT COUNT(*) FROM notification WHERE notice_type = ? AND notice_date = ?")
                .params(noticeType, noticeDate)
                .query(Long.class)
                .single();
    }
}
