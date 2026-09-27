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
 * 例行完成回报数据访问：plan_completion 表。迟到完成仅登记，不改变计划状态。
 */
@Repository
public class PlanCompletionRepository {

    private static final RowMapper<PlanCompletionRecord> MAPPER = (rs, rowNum) -> new PlanCompletionRecord(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getTimestamp("completed_at").toInstant(),
            rs.getBoolean("late"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public PlanCompletionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PlanCompletionRecord insert(PlanCompletionRecord record) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO plan_completion (plan_id, completed_at, late, request_id, created_at)"
                            + " VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, record.planId());
            ps.setTimestamp(2, Timestamp.from(record.completedAt()));
            ps.setBoolean(3, record.late());
            ps.setString(4, record.requestId());
            ps.setTimestamp(5, Timestamp.from(record.createdAt()));
            return ps;
        }, keyHolder);
        long id = Objects.requireNonNull(keyHolder.getKey()).longValue();
        return new PlanCompletionRecord(id, record.planId(), record.completedAt(), record.late(),
                record.requestId(), record.createdAt());
    }

    public List<PlanCompletionRecord> findByPlanId(long planId) {
        return jdbc.query("SELECT * FROM plan_completion WHERE plan_id = ? ORDER BY completed_at, id",
                MAPPER, planId);
    }
}
