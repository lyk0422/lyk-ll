package com.example.starter.dimming;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 事故锁定/解除留痕数据访问；留痕与状态变更在同一事务内写入。
 */
@Repository
public class AccidentLockRepository {

    private final JdbcTemplate jdbc;

    public AccidentLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String circuitCode, String action, String alarmId, String requestId,
            String detail, LocalDateTime createdAt) {
        jdbc.update(
                "INSERT INTO accident_lock_log (circuit_code, action, alarm_id, request_id, detail, created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                circuitCode, action, alarmId, requestId, detail, createdAt);
    }

    public int countByCircuitAndAction(String circuitCode, String action) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM accident_lock_log WHERE circuit_code = ? AND action = ?",
                Integer.class, circuitCode, action);
        return count == null ? 0 : count;
    }

    public List<String> findActions(String circuitCode) {
        return jdbc.query(
                "SELECT action FROM accident_lock_log WHERE circuit_code = ? ORDER BY id",
                (rs, rowNum) -> rs.getString(1), circuitCode);
    }
}
