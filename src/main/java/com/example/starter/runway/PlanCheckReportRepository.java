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
import java.util.Objects;

/**
 * 例行巡检检查回报数据访问：plan_check_report 表。
 */
@Repository
public class PlanCheckReportRepository {

    private static final RowMapper<PlanCheckReport> MAPPER = (rs, rowNum) -> new PlanCheckReport(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getTimestamp("reported_at").toInstant(),
            rs.getString("completed_items"),
            rs.getBoolean("complete"),
            rs.getBoolean("late"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public PlanCheckReportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PlanCheckReport insert(PlanCheckReport report) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO plan_check_report (plan_id, reported_at, completed_items, complete, late,"
                            + " request_id, created_at) VALUES (?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, report.planId());
            ps.setTimestamp(2, Timestamp.from(report.reportedAt()));
            ps.setString(3, report.completedItems());
            ps.setBoolean(4, report.complete());
            ps.setBoolean(5, report.late());
            ps.setString(6, report.requestId());
            ps.setTimestamp(7, Timestamp.from(report.createdAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return new PlanCheckReport(id, report.planId(), report.reportedAt(), report.completedItems(),
                report.complete(), report.late(), report.requestId(), report.createdAt());
    }

    public List<PlanCheckReport> findByPlanId(long planId) {
        return jdbc.query("SELECT * FROM plan_check_report WHERE plan_id = ? ORDER BY id", MAPPER, planId);
    }
}
