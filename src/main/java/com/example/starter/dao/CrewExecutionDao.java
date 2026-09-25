package com.example.starter.dao;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 班组执行占用：crew_execution 中一个班组至多一行，主键约束保证同一班组同时刻唯一执行单。
 */
@Repository
public class CrewExecutionDao {

    private final JdbcTemplate jdbc;

    public CrewExecutionDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 尝试占用班组；返回 false 表示班组已有执行中工单（资源冲突）。
     */
    public boolean tryOccupy(long crewId, long orderId, Instant assignedTime) {
        try {
            jdbc.update(
                    "INSERT INTO crew_execution (crew_id, order_id, assigned_time) VALUES (?, ?, ?)",
                    crewId, orderId, Timestamp.from(assignedTime));
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Long currentOrderId(long crewId) {
        List<Long> ids = jdbc.queryForList(
                "SELECT order_id FROM crew_execution WHERE crew_id = ?", Long.class, crewId);
        return ids.stream().findFirst().orElse(null);
    }

    public void release(long crewId) {
        jdbc.update("DELETE FROM crew_execution WHERE crew_id = ?", crewId);
    }

    public void releaseByOrder(long orderId) {
        jdbc.update("DELETE FROM crew_execution WHERE order_id = ?", orderId);
    }

    public long countAll() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM crew_execution", Long.class);
        return n == null ? 0 : n;
    }
}
