package com.example.starter.forestry.repo;

import java.sql.Date;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 限批名单仓储。
 */
@Repository
public class RestrictionRepository {

    private final JdbcTemplate jdbc;

    public RestrictionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 判断经营主体在指定日期是否处于限批期内（含截止当日）。
     */
    public boolean isRestricted(String operatorId, LocalDate date) {
        Integer n = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM restriction_list
                         WHERE operator_id = ? AND active = 1 AND start_date <= ? AND end_date >= ?
                        """,
                Integer.class, operatorId, Date.valueOf(date), Date.valueOf(date));
        return n != null && n > 0;
    }

    /**
     * 将经营主体列入限批名单。
     */
    public void add(String operatorId, String reason, LocalDate startDate, LocalDate endDate) {
        jdbc.update("""
                        INSERT INTO restriction_list (operator_id, reason, start_date, end_date, active)
                        VALUES (?, ?, ?, ?, 1)
                        """,
                operatorId, reason, Date.valueOf(startDate), Date.valueOf(endDate));
    }
}
