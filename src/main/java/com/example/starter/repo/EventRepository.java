package com.example.starter.repo;

import com.example.starter.domain.EventType;
import com.example.starter.domain.ZoneEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 展示池事件流水数据访问。
 */
@Repository
public class EventRepository {

    private final JdbcClient jdbc;

    public EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加事件流水。
     */
    public void insert(long zoneId, EventType type, String fromStatus, String toStatus,
                       String detail, LocalDateTime occurredAt) {
        jdbc.sql("INSERT INTO zone_event (zone_id, event_type, from_status, to_status, detail, occurred_at)"
                        + " VALUES (:zoneId, :type, :fromStatus, :toStatus, :detail, :occurredAt)")
                .param("zoneId", zoneId)
                .param("type", type.name())
                .param("fromStatus", fromStatus)
                .param("toStatus", toStatus)
                .param("detail", detail)
                .param("occurredAt", occurredAt)
                .update();
    }

    public List<ZoneEvent> findByZone(long zoneId) {
        return jdbc.sql("SELECT * FROM zone_event WHERE zone_id = :zoneId ORDER BY occurred_at, id")
                .param("zoneId", zoneId)
                .query(ZoneEvent.class)
                .list();
    }

    /**
     * 查询指定时间区间内、携带状态变更信息的事件，按时间升序。
     */
    public List<ZoneEvent> findStatusEventsBetween(long zoneId, LocalDateTime start, LocalDateTime end) {
        return jdbc.sql("SELECT * FROM zone_event WHERE zone_id = :zoneId AND to_status IS NOT NULL"
                        + " AND occurred_at >= :start AND occurred_at < :end ORDER BY occurred_at, id")
                .param("zoneId", zoneId)
                .param("start", start)
                .param("end", end)
                .query(ZoneEvent.class)
                .list();
    }

    /**
     * 查询指定时刻之前最近一次状态变更，用于确定区间起点的池状态。
     */
    public Optional<ZoneEvent> findLastStatusEventBefore(long zoneId, LocalDateTime time) {
        return jdbc.sql("SELECT * FROM zone_event WHERE zone_id = :zoneId AND to_status IS NOT NULL"
                        + " AND occurred_at < :time ORDER BY occurred_at DESC, id DESC LIMIT 1")
                .param("zoneId", zoneId)
                .param("time", time)
                .query(ZoneEvent.class)
                .optional();
    }
}
