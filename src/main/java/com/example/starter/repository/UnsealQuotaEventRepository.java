package com.example.starter.repository;

import com.example.starter.domain.UnsealQuotaEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 解封额度轨迹数据访问：事件追加与按解封单查询。
 */
@Repository
public class UnsealQuotaEventRepository {

    private static final RowMapper<UnsealQuotaEvent> MAPPER = (rs, n) -> new UnsealQuotaEvent(
            rs.getLong("id"), rs.getLong("order_id"), rs.getLong("valve_id"),
            UnsealQuotaEvent.EventType.valueOf(rs.getString("event_type")),
            rs.getInt("quota_used"),
            Optional.ofNullable(rs.getObject("task_id")).map(o -> ((Number) o).longValue()).orElse(null),
            rs.getString("detail"), rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public UnsealQuotaEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加额度轨迹事件；必须与对应业务变更在同一事务内调用。
     */
    public void insert(UnsealQuotaEvent event) {
        jdbc.update("INSERT INTO unseal_quota_event"
                        + " (order_id, valve_id, event_type, quota_used, task_id, detail, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?)",
                event.orderId(), event.valveId(), event.eventType().name(), event.quotaUsed(),
                event.taskId(), event.detail(), Timestamp.from(event.createdAt()));
    }

    /**
     * 按解封单查询完整额度轨迹，按主键升序。
     */
    public List<UnsealQuotaEvent> findByOrderId(long orderId) {
        return jdbc.query("SELECT id, order_id, valve_id, event_type, quota_used, task_id, detail, created_at"
                + " FROM unseal_quota_event WHERE order_id = ? ORDER BY id", MAPPER, orderId);
    }
}
