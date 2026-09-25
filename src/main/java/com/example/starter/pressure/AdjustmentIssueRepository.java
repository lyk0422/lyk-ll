package com.example.starter.pressure;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

/**
 * 调节指令仓储：PRESSURE 调压与 INJECTION 加注两条指令必须同一事务下发。
 */
@Repository
public class AdjustmentIssueRepository {

    private final JdbcTemplate jdbc;

    public AdjustmentIssueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Long taskId, Long stationId, String issueType,
                       BigDecimal beforeValue, BigDecimal afterValue) {
        jdbc.update("""
                        INSERT INTO adjustment_issue
                            (task_id, station_id, issue_type, before_value, after_value, created_at)
                        VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """,
                taskId, stationId, issueType, beforeValue, afterValue);
    }

    public long countByTask(Long taskId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ?", Long.class, taskId);
        return count == null ? 0 : count;
    }
}
