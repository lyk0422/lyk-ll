package com.example.starter.valve;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;

/**
 * 锁阀／复开工单仓储：LOCK 与 REOPEN 均须两名不同操作人，单人/同人工单停留 PENDING 不生效。
 */
@Repository
public class LockOrderRepository {

    private final JdbcTemplate jdbc;

    public LockOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Long insert(Long stationId, String action, String status,
                       String firstOperator, String secondOperator, String triggerRequestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO lock_order
                                (station_id, action, status, first_operator, second_operator,
                                 trigger_request_id, created_at)
                            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                            """,
                    new String[]{"id"});
            ps.setLong(1, stationId);
            ps.setString(2, action);
            ps.setString(3, status);
            ps.setString(4, firstOperator);
            ps.setString(5, secondOperator);
            ps.setString(6, triggerRequestId);
            return ps;
        }, keyHolder);
        return keyHolder.getKey() == null ? null : keyHolder.getKey().longValue();
    }
}
