package com.example.starter.forestry.repo;

import java.sql.Date;
import java.time.LocalDate;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 归档后迟到数据事件仓储：仅记录，不驱动业务。
 */
@Repository
public class LateEventRepository {

    private final JdbcTemplate jdbc;

    public LateEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String applicationId, String licenseId, String eventType,
                       String payload, LocalDate date) {
        jdbc.update("""
                        INSERT INTO late_event (application_id, license_id, event_type, payload, created_date)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                applicationId, licenseId, eventType, payload, Date.valueOf(date));
    }
}
