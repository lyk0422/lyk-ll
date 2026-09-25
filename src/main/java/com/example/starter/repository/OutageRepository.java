package com.example.starter.repository;

import com.example.starter.domain.OutageEvent;
import com.example.starter.domain.OutageStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 停电事件访问：按“基站＋停电起始时刻”唯一去重。
 */
@Repository
public class OutageRepository {

    private final JdbcTemplate jdbc;

    public OutageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 插入停电事件；唯一键冲突（重复告警合并）返回 false，不产生新事件。
     */
    public boolean insertIfAbsent(OutageEvent event) {
        try {
            int rows = jdbc.update("""
                            INSERT INTO outage_event (station_id, outage_start_at, status, restored_at, created_at)
                            VALUES (?, ?, 'ACTIVE', NULL, ?)
                            """,
                    event.stationId(), Timestamp.from(event.outageStartAt()),
                    Timestamp.from(event.createdAt()));
            return rows == 1;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public Optional<OutageEvent> find(long stationId, Instant outageStartAt) {
        return jdbc.query("""
                        SELECT * FROM outage_event
                         WHERE station_id = ? AND outage_start_at = ?
                        """,
                OutageEvent.rowMapper(), stationId, Timestamp.from(outageStartAt))
                .stream().findFirst();
    }

    /** 基站当前停电中的事件（至多一条）。 */
    public Optional<OutageEvent> findActiveByStation(long stationId) {
        return jdbc.query("""
                        SELECT * FROM outage_event
                         WHERE station_id = ? AND status = 'ACTIVE'
                         ORDER BY outage_start_at DESC
                         LIMIT 1
                        """,
                OutageEvent.rowMapper(), stationId)
                .stream().findFirst();
    }

    /**
     * 行锁定读取事件，供恢复并发与告警合并判定。
     */
    public Optional<OutageEvent> findByIdForUpdate(long eventId) {
        return jdbc.query("SELECT * FROM outage_event WHERE id = ? FOR UPDATE",
                OutageEvent.rowMapper(), eventId).stream().findFirst();
    }

    /**
     * 条件恢复：仅 ACTIVE 事件可转 RESTORED。与油机确认并发时靠条件更新决出唯一方向。
     *
     * @return true 本次调用完成恢复；false 事件已恢复或不存在
     */
    public boolean markRestoredIfActive(long eventId, Instant restoredAt) {
        int rows = jdbc.update("""
                        UPDATE outage_event
                           SET status = ?, restored_at = ?
                         WHERE id = ? AND status = 'ACTIVE'
                        """,
                OutageStatus.RESTORED.name(), Timestamp.from(restoredAt), eventId);
        return rows == 1;
    }

    public List<OutageEvent> findByStation(long stationId) {
        return jdbc.query("SELECT * FROM outage_event WHERE station_id = ? ORDER BY outage_start_at",
                OutageEvent.rowMapper(), stationId);
    }
}
