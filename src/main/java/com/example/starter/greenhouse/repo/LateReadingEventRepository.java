package com.example.starter.greenhouse.repo;

import com.example.starter.greenhouse.po.LateReadingEvent;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

/**
 * 迟到读数事件数据访问。
 */
@Repository
public class LateReadingEventRepository {

    private final JdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;

    public LateReadingEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(jdbc)
                .withTableName("late_reading_event").usingGeneratedKeyColumns("id");
    }

    public void insert(LateReadingEvent e) {
        var p = new HashMap<String, Object>();
        p.put("zone_id", e.getZoneId());
        p.put("reading_id", e.getReadingId());
        p.put("sample_time", Timestamp.from(e.getSampleTime()));
        p.put("budget_day", java.sql.Date.valueOf(e.getBudgetDay()));
        p.put("created_at", Timestamp.from(e.getCreatedAt()));
        insert.execute(p);
    }

    /** 统计分区在某 UTC 日的迟到读数事件数。 */
    public long countByZoneAndDay(long zoneId, LocalDate day) {
        Long c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_reading_event WHERE zone_id = ? AND budget_day = ?",
                Long.class, zoneId, java.sql.Date.valueOf(day));
        return c == null ? 0 : c;
    }
}
