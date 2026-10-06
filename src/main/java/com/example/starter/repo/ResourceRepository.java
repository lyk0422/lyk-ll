package com.example.starter.repo;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 引航员、拖轮及计划-拖轮组合的数据访问。
 */
@Repository
public class ResourceRepository {

    private final JdbcTemplate jdbc;

    public ResourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean pilotExists(long pilotId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pilot WHERE id = ?", Integer.class, pilotId);
        return count != null && count > 0;
    }

    public List<Long> findExistingTugIds(List<Long> tugIds) {
        if (tugIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", tugIds.stream().map(id -> "?").toList());
        return jdbc.queryForList("SELECT id FROM tugboat WHERE id IN (" + placeholders + ")",
                Long.class, tugIds.toArray());
    }

    public void replacePlanTugs(long planId, List<Long> tugIds) {
        jdbc.update("DELETE FROM pilot_plan_tug WHERE plan_id = ?", planId);
        for (Long tugId : tugIds) {
            jdbc.update("INSERT INTO pilot_plan_tug (plan_id, tugboat_id) VALUES (?,?)", planId, tugId);
        }
    }

    public List<Long> findTugIdsByPlan(long planId) {
        return jdbc.queryForList("SELECT tugboat_id FROM pilot_plan_tug WHERE plan_id = ? ORDER BY tugboat_id",
                Long.class, planId);
    }

    public void deleteAllPlanTugs() {
        jdbc.update("DELETE FROM pilot_plan_tug");
    }
}
