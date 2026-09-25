package com.example.starter.repo;

import java.sql.Date;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 当日禁烧记录访问，按窑+日期拦截新执行方案。
 */
@Repository
public class BanRepository {

    private final JdbcTemplate jdbc;

    public BanRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long planId, String kilnId, LocalDate banDate, String reason) {
        jdbc.update("INSERT INTO ban_record (plan_id, kiln_id, ban_date, reason) VALUES (?, ?, ?, ?)",
                planId, kilnId, Date.valueOf(banDate), reason);
    }

    public boolean exists(String kilnId, LocalDate banDate) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ban_record WHERE kiln_id = ? AND ban_date = ?",
                Integer.class, kilnId, Date.valueOf(banDate));
        return c != null && c > 0;
    }
}
