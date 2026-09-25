package com.example.starter.transition.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 归档后迟到检验事件数据访问。
 */
@Repository
public class LateEventRepository {

    private final JdbcTemplate jdbc;

    public LateEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String orderNo, String rollNo, String requestId, String payload) {
        jdbc.update("INSERT INTO late_inspection_event (order_no, roll_no, request_id, payload) "
                + "VALUES (?, ?, ?, ?)", orderNo, rollNo, requestId, payload);
    }

    public int countByOrderNo(String orderNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_inspection_event WHERE order_no = ?", Integer.class, orderNo);
        return count == null ? 0 : count;
    }
}
