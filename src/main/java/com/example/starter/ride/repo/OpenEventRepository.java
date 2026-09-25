package com.example.starter.ride.repo;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 开放事件仓储：(设备, 日期, 开放序号) 唯一，保证并发开放唯一成功。
 */
@Repository
public class OpenEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public OpenEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return 是否插入成功；唯一约束冲突返回 false。 */
    public boolean insertIfAbsent(String requestId, String rideId, LocalDate bizDate,
                                  int openSeq, Instant createdAt) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO open_event (request_id, ride_id, biz_date, open_seq, created_at) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    requestId, rideId, bizDate, openSeq, Timestamp.from(createdAt));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }
}
