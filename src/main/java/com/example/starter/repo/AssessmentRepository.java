package com.example.starter.repo;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 安全评估记录数据访问；同线路同日期仅允许一条评估（数据库唯一约束）。
 */
@Repository
public class AssessmentRepository {

    private final JdbcClient jdbc;

    public AssessmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 评估记录。
     */
    public record AssessmentRow(long id, long routeId, LocalDate assessmentDate,
                                boolean spacingOk, boolean roadOk, boolean seatsOk,
                                String result, String rejectReason) {
    }

    /**
     * 写入评估记录，返回自增ID；同线路同日期重复时抛出 DuplicateKeyException。
     */
    public long insert(long routeId, LocalDate assessmentDate, boolean spacingOk, boolean roadOk,
                       boolean seatsOk, String result, String rejectReason, String requestId) {
        jdbc.sql("INSERT INTO safety_assessment (route_id, assessment_date, spacing_ok, road_ok,"
                        + " seats_ok, result, reject_reason, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(routeId, assessmentDate, spacingOk, roadOk, seatsOk, result, rejectReason,
                        requestId)
                .update();
        return jdbc.sql("SELECT MAX(id) FROM safety_assessment WHERE route_id = ? AND assessment_date = ?")
                .params(routeId, assessmentDate)
                .query(Long.class)
                .single();
    }

    public Optional<AssessmentRow> findByRouteAndDate(long routeId, LocalDate date) {
        return jdbc.sql("SELECT id, route_id, assessment_date, spacing_ok, road_ok, seats_ok,"
                        + " result, reject_reason FROM safety_assessment"
                        + " WHERE route_id = ? AND assessment_date = ?")
                .params(routeId, date)
                .query((rs, n) -> new AssessmentRow(rs.getLong(1), rs.getLong(2),
                        rs.getDate(3).toLocalDate(), rs.getBoolean(4), rs.getBoolean(5),
                        rs.getBoolean(6), rs.getString(7), rs.getString(8)))
                .optional();
    }
}
