package com.example.starter.repo;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 迟到取消合成违约记录数据访问。
 */
@Repository
public class BreachRepository {

    private final JdbcTemplate jdbc;

    public BreachRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long planId, String reason, LocalDateTime createdAt) {
        jdbc.update("INSERT INTO breach_record (plan_id, reason, created_at) VALUES (?,?,?)",
                planId, reason, Timestamp.valueOf(createdAt));
    }

    public int countByPlan(long planId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM breach_record WHERE plan_id = ?",
                Integer.class, planId);
        return count == null ? 0 : count;
    }

    public List<String> findReasonsByPlan(long planId) {
        return jdbc.queryForList("SELECT reason FROM breach_record WHERE plan_id = ? ORDER BY id",
                String.class, planId);
    }

    public void deleteAll() {
        jdbc.update("DELETE FROM breach_record");
    }
}
