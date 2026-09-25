package com.example.starter.atm.repo;

import com.example.starter.atm.domain.PlanEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 计划事件流水：押运异常、封存后迟到数据等均登记于此，供复算审计。
 */
@Repository
public class PlanEventRepository {

    private static final RowMapper<PlanEvent> MAPPER = (rs, n) -> new PlanEvent(
            rs.getLong("id"),
            rs.getLong("plan_id"),
            rs.getString("event_type"),
            rs.getInt("late") == 1,
            rs.getString("detail"),
            rs.getLong("event_at"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public PlanEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long planId, String eventType, boolean late,
                       String detail, long eventAt, long createdAt) {
        jdbc.update(
                "INSERT INTO plan_event (plan_id, event_type, late, detail, event_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                planId, eventType, late ? 1 : 0, detail, eventAt, createdAt);
    }

    public List<PlanEvent> findByPlanId(long planId) {
        return jdbc.query(
                "SELECT * FROM plan_event WHERE plan_id = ? ORDER BY id", MAPPER, planId);
    }

    public List<PlanEvent> findAll() {
        return jdbc.query("SELECT * FROM plan_event ORDER BY id", MAPPER);
    }
}
