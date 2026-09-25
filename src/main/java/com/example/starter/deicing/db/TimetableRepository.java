package com.example.starter.deicing.db;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 航班时刻表数据访问，提供两步法 HOT 分钟数。
 */
@Repository
public class TimetableRepository {

    private final JdbcTemplate jdbcTemplate;

    public TimetableRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询两步法防冰液保持时间；时刻表无该航班时返回空。
     */
    public Integer findHotMinutes(String flightNumber) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT hot_minutes FROM flight_timetable WHERE flight_number = ?",
                    Integer.class, flightNumber);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    /**
     * 新增或更新航班的两步法 HOT（合成时刻表维护）。
     */
    public void upsertHotMinutes(String flightNumber, int hotMinutes) {
        jdbcTemplate.update("""
                        MERGE INTO flight_timetable (flight_number, hot_minutes, updated_at)
                        KEY (flight_number)
                        VALUES (?, ?, CURRENT_TIMESTAMP(3))
                        """,
                flightNumber, hotMinutes);
        jdbcTemplate.update(
                "MERGE INTO flight_latch (flight_number) KEY (flight_number) VALUES (?)",
                flightNumber);
    }
}
