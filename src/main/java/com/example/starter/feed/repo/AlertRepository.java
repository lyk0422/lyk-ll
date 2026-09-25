package com.example.starter.feed.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 合规告警仓储。
 */
@Repository
public class AlertRepository {

    private final JdbcTemplate jdbc;

    public AlertRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String alertType, String batchNo, Long feedingId, String detail) {
        jdbc.update("INSERT INTO compliance_alert (alert_type, batch_no, feeding_id, detail) VALUES (?,?,?,?)",
                alertType, batchNo, feedingId, detail);
    }

    public long countByBatchAndType(String batchNo, String alertType) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM compliance_alert WHERE batch_no = ? AND alert_type = ?",
                Long.class, batchNo, alertType);
        return count == null ? 0 : count;
    }
}
