package com.example.starter.dye.repo;

import com.example.starter.dye.domain.LateColorEventPO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * 迟到测色事件仓储。
 */
@Repository
public class LateColorEventRepository {

    private final JdbcTemplate jdbc;

    public LateColorEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long runId, String runNo, java.math.BigDecimal deltaE, LocalDateTime now) {
        jdbc.update("insert into late_color_event (run_id, run_no, delta_e, created_at) values (?, ?, ?, ?)",
                runId, runNo, deltaE, Timestamp.valueOf(now));
    }

    public int countByRun(long runId) {
        Integer c = jdbc.queryForObject("select count(*) from late_color_event where run_id = ?",
                Integer.class, runId);
        return c == null ? 0 : c;
    }
}
