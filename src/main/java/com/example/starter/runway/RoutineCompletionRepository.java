package com.example.starter.runway;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

/**
 * 例行巡检完成回报数据访问：routine_completion_report 表。
 */
@Repository
public class RoutineCompletionRepository {

    private static final RowMapper<RoutineCompletionReport> MAPPER = (rs, rowNum) -> new RoutineCompletionReport(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getTimestamp("completed_at").toInstant(),
            rs.getInt("late") != 0,
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public RoutineCompletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RoutineCompletionReport insert(RoutineCompletionReport report) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO routine_completion_report (plan_id, completed_at, late, request_id, created_at)"
                            + " VALUES (?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, report.planId());
            ps.setTimestamp(2, Timestamp.from(report.completedAt()));
            ps.setInt(3, report.late() ? 1 : 0);
            ps.setString(4, report.requestId());
            ps.setTimestamp(5, Timestamp.from(report.createdAt()));
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey() == null ? 0L : keyHolder.getKey().longValue();
        return new RoutineCompletionReport(id, report.planId(), report.completedAt(), report.late(),
                report.requestId(), report.createdAt());
    }

    public List<RoutineCompletionReport> findByPlanId(long planId) {
        return jdbc.query("SELECT * FROM routine_completion_report WHERE plan_id = ? ORDER BY id",
                MAPPER, planId);
    }
}
