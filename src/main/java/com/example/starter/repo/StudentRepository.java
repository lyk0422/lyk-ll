package com.example.starter.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 学生与上下车打卡数据访问；打卡按学生+日期+类型数据库唯一约束去重。
 */
@Repository
public class StudentRepository {

    private final JdbcClient jdbc;

    public StudentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 学生记录。
     */
    public record StudentRow(long id, String studentNo, String name, Long routeId) {
    }

    /**
     * 打卡记录。
     */
    public record CheckInRow(long id, long studentId, long routeId, LocalDate checkDate,
                             String checkType) {
    }

    public Optional<StudentRow> findStudentById(long id) {
        return jdbc.sql("SELECT id, student_no, name, route_id FROM student WHERE id = ?")
                .param(id)
                .query((rs, n) -> new StudentRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        (Long) rs.getObject(4)))
                .optional();
    }

    /**
     * 线路全部学生。
     */
    public List<StudentRow> findStudentsByRoute(long routeId) {
        return jdbc.sql("SELECT id, student_no, name, route_id FROM student WHERE route_id = ?"
                        + " ORDER BY id")
                .param(routeId)
                .query((rs, n) -> new StudentRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        (Long) rs.getObject(4)))
                .list();
    }

    public void updateStudentRoute(long studentId, Long routeId) {
        jdbc.sql("UPDATE student SET route_id = ? WHERE id = ?")
                .params(routeId, studentId)
                .update();
    }

    /**
     * 写入打卡，返回自增ID；学生+日期+类型重复时抛出 DuplicateKeyException。
     */
    public long insertCheckIn(long studentId, long routeId, LocalDate checkDate, String checkType,
                              String requestId) {
        jdbc.sql("INSERT INTO check_in (student_id, route_id, check_date, check_type, request_id)"
                        + " VALUES (?, ?, ?, ?, ?)")
                .params(studentId, routeId, checkDate, checkType, requestId)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM check_in WHERE student_id = ? AND check_date = ?"
                        + " AND check_type = ?")
                .params(studentId, checkDate, checkType)
                .query(Long.class)
                .single();
    }

    public Optional<CheckInRow> findCheckIn(long studentId, LocalDate checkDate, String checkType) {
        return jdbc.sql("SELECT id, student_id, route_id, check_date, check_type FROM check_in"
                        + " WHERE student_id = ? AND check_date = ? AND check_type = ?")
                .params(studentId, checkDate, checkType)
                .query((rs, n) -> new CheckInRow(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                        rs.getDate(4).toLocalDate(), rs.getString(5)))
                .optional();
    }

    /**
     * 线路当日已上车打卡的学生ID。
     */
    public List<Long> findBoardedStudentIds(long routeId, LocalDate date) {
        return jdbc.sql("SELECT student_id FROM check_in WHERE route_id = ? AND check_date = ?"
                        + " AND check_type = 'BOARD'")
                .params(routeId, date)
                .query(Long.class)
                .list();
    }

    /**
     * 指定日期的打卡总数（日结复算用）。
     */
    public long countCheckInsByDate(LocalDate date) {
        return jdbc.sql("SELECT COUNT(*) FROM check_in WHERE check_date = ?")
                .param(date)
                .query(Long.class)
                .single();
    }
}
