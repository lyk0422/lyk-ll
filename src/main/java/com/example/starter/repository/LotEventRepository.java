package com.example.starter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * 批次事件留痕数据访问。
 */
@Repository
public class LotEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public LotEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(long lotId, String eventType, String detail, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO lot_event (lot_id, event_type, detail, created_at)
                VALUES (?, ?, ?, ?)
                """,
                lotId, eventType, detail, Timestamp.from(now));
    }
}
