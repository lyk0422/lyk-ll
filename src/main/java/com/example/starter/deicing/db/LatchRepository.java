package com.example.starter.deicing.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 行级闩锁：航班申请闩锁串行化同航班申请；全局调度闩锁串行化出队分配。
 */
@Repository
public class LatchRepository {

    private final JdbcTemplate jdbcTemplate;

    public LatchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 锁定指定航班闩锁行（不存在则创建）。必须在事务内调用。
     */
    public void lockFlight(String flightNumber) {
        jdbcTemplate.update(
                "MERGE INTO flight_latch (flight_number) KEY (flight_number) VALUES (?)",
                flightNumber);
        jdbcTemplate.queryForObject(
                "SELECT flight_number FROM flight_latch WHERE flight_number = ? FOR UPDATE",
                String.class, flightNumber);
    }

    /**
     * 锁定全局调度闩锁，串行化所有出队车位分配。必须在事务内调用。
     */
    public void lockSchedule() {
        jdbcTemplate.queryForObject("SELECT id FROM schedule_latch WHERE id = 1 FOR UPDATE",
                Integer.class);
    }

    /**
     * 锁定全局库存闩锁，串行化预占、结算与借用。必须在事务内调用。
     */
    public void lockInventory() {
        jdbcTemplate.queryForObject("SELECT id FROM inventory_latch WHERE id = 1 FOR UPDATE",
                Integer.class);
    }
}
