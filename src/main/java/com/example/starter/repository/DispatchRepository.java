package com.example.starter.repository;

import com.example.starter.domain.DispatchOrder;
import com.example.starter.domain.GeneratorStatus;
import com.example.starter.domain.OrderEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 调度单及占坑表访问。
 *
 * <p>并发唯一性依赖两张占坑表的主键冲突：
 * {@code station_active_order} 保证同站同时刻一张活动单，
 * {@code generator_active_order} 保证同油机同时刻只服务一站。
 */
@Repository
public class DispatchRepository {

    private final JdbcTemplate jdbc;

    public DispatchRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建调度单（PENDING），返回生成的主键。 */
    public long insertOrder(String requestId, long stationId, long eventId,
                            Instant arrivalDeadlineUtc, Instant dispatchDeadline, Instant now) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                            INSERT INTO dispatch_order
                                (request_id, station_id, event_id, gen_id, status,
                                 arrival_deadline_utc, dispatch_deadline,
                                 confirmed_at, abnormal_at, released_at, closed_at,
                                 created_at, updated_at)
                            VALUES (?, ?, ?, NULL, 'PENDING', ?, ?, NULL, NULL, NULL, NULL, ?, ?)
                            """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, requestId);
            ps.setLong(2, stationId);
            ps.setLong(3, eventId);
            ps.setTimestamp(4, Timestamp.from(arrivalDeadlineUtc));
            ps.setTimestamp(5, Timestamp.from(dispatchDeadline));
            ps.setTimestamp(6, Timestamp.from(now));
            ps.setTimestamp(7, Timestamp.from(now));
            return ps;
        }, keyHolder);
        return keyHolder.getKeyAs(Long.class);
    }

    public Optional<DispatchOrder> findById(long orderId) {
        return jdbc.query("SELECT * FROM dispatch_order WHERE id = ?",
                DispatchOrder.rowMapper(), orderId).stream().findFirst();
    }

    public DispatchOrder getById(long orderId) {
        return findById(orderId).orElseThrow();
    }

    public Optional<DispatchOrder> findByRequestId(String requestId) {
        return jdbc.query("SELECT * FROM dispatch_order WHERE request_id = ?",
                DispatchOrder.rowMapper(), requestId).stream().findFirst();
    }

    /** 行锁读取调度单，供确认/改派/结案/恢复竞态判定。 */
    public Optional<DispatchOrder> lockById(long orderId) {
        return jdbc.query("SELECT * FROM dispatch_order WHERE id = ? FOR UPDATE",
                DispatchOrder.rowMapper(), orderId).stream().findFirst();
    }

    /**
     * 占用基站活动单坑位；同站已有活动单时主键冲突返回 false。
     */
    public boolean tryAcquireStationLock(long stationId, long orderId) {
        try {
            jdbc.update("INSERT INTO station_active_order (station_id, order_id) VALUES (?, ?)",
                    stationId, orderId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 占用油机坑位；油机已在途/发电中时主键冲突返回 false，保证并发分配唯一成功。
     */
    public boolean tryAcquireGeneratorLock(long genId, long orderId) {
        try {
            jdbc.update("INSERT INTO generator_active_order (gen_id, order_id) VALUES (?, ?)",
                    genId, orderId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /** 释放油机坑位（改派换下旧油机或解除时调用）。 */
    public void releaseGeneratorLock(long genId) {
        jdbc.update("DELETE FROM generator_active_order WHERE gen_id = ?", genId);
    }

    /** 释放基站坑位（单据进入终态时调用）。 */
    public void releaseStationLock(long stationId) {
        jdbc.update("DELETE FROM station_active_order WHERE station_id = ?", stationId);
    }

    /** 查询占着基站坑位的活动单 ID。 */
    public Optional<Long> findActiveOrderIdByStation(long stationId) {
        try {
            Long id = jdbc.queryForObject(
                    "SELECT order_id FROM station_active_order WHERE station_id = ?",
                    Long.class, stationId);
            return Optional.ofNullable(id);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 派单：条件更新仅在单据仍为 PENDING 时生效；派单截止时刻在建单时已写定。
     */
    public int markDispatched(long orderId, long genId, Instant now) {
        return jdbc.update("""
                        UPDATE dispatch_order
                           SET gen_id = ?, status = 'DISPATCHED', updated_at = ?
                         WHERE id = ? AND status = 'PENDING'
                        """,
                genId, Timestamp.from(now), orderId);
    }

    /**
     * 改派：仅异常态单据可改派，换新油机并重设计时 30 分钟；原油机由调用方先释放。
     */
    public int applyReassignment(long orderId, long newGenId, Instant newDispatchDeadline, Instant now) {
        return jdbc.update("""
                        UPDATE dispatch_order
                           SET gen_id = ?, status = 'DISPATCHED',
                               dispatch_deadline = ?, updated_at = ?
                         WHERE id = ? AND status = 'GENERATION_ABNORMAL'
                        """,
                newGenId, Timestamp.from(newDispatchDeadline), Timestamp.from(now), orderId);
    }

    /** 按主键条件占用油机并置在途；仅 IDLE 油机可被占用。 */
    public boolean occupyGeneratorIfIdle(long genId, long orderId) {
        int rows = jdbc.update("""
                        UPDATE generator
                           SET status = 'DISPATCHED', current_order_id = ?
                         WHERE id = ? AND status = 'IDLE'
                        """, orderId, genId);
        return rows == 1;
    }

    /** 无条件释放油机为空闲（调用方须已持有该油机坑位并在同事务内）。 */
    public void freeGenerator(long genId) {
        jdbc.update("UPDATE generator SET status = 'IDLE', current_order_id = NULL WHERE id = ?",
                genId);
    }

    /** 油机进入发电中。 */
    public int markGeneratorStatus(long genId, GeneratorStatus status, Long orderId) {
        return jdbc.update("UPDATE generator SET status = ?, current_order_id = ? WHERE id = ?",
                status.name(), orderId, genId);
    }

    /**
     * 发电确认：仅 DISPATCHED 单据可确认。与恢复并发时由行锁+条件更新决出唯一方向。
     */
    public int confirmGeneration(long orderId, long genId, Instant confirmedAt) {
        return jdbc.update("""
                        UPDATE dispatch_order
                           SET status = 'GENERATING', confirmed_at = ?, updated_at = ?
                         WHERE id = ? AND gen_id = ? AND status = 'DISPATCHED'
                        """,
                Timestamp.from(confirmedAt), Timestamp.from(confirmedAt), orderId, genId);
    }

    /** 确认超时升级异常：仅 DISPATCHED 且未确认单据可升级。 */
    public int markAbnormal(long orderId, Instant at) {
        return jdbc.update("""
                        UPDATE dispatch_order
                           SET status = 'GENERATION_ABNORMAL', abnormal_at = ?, updated_at = ?
                         WHERE id = ? AND status = 'DISPATCHED' AND confirmed_at IS NULL
                        """, Timestamp.from(at), Timestamp.from(at), orderId);
    }

    /**
     * 市电恢复解除：非终态单据（含在途/发电中/异常）统一转 RELEASED。
     * 已是终态返回 0，用于恢复与确认竞态的唯一终态判定。
     */
    public int markReleasedIfActive(long orderId, Instant releasedAt) {
        return jdbc.update("""
                        UPDATE dispatch_order
                           SET status = 'RELEASED', released_at = ?, updated_at = ?
                         WHERE id = ? AND status NOT IN ('RELEASED', 'CLOSED')
                        """, Timestamp.from(releasedAt), Timestamp.from(releasedAt), orderId);
    }

    /** 结案：非终态单据转 CLOSED；已 CLOSED 返回 0（重复结案按幂等成功处理）。 */
    public int closeIfOpen(long orderId, Instant closedAt) {
        return jdbc.update("""
                        UPDATE dispatch_order
                           SET status = 'CLOSED', closed_at = ?, updated_at = ?
                         WHERE id = ? AND status NOT IN ('RELEASED', 'CLOSED')
                        """, Timestamp.from(closedAt), Timestamp.from(closedAt), orderId);
    }

    /** 记录生命周期事件。 */
    public void insertEvent(long orderId, String eventType, Long genId, Instant eventAt, String note) {
        jdbc.update("""
                        INSERT INTO order_event (order_id, event_type, gen_id, event_at, note, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                orderId, eventType, genId, Timestamp.from(eventAt), note, Timestamp.from(eventAt));
    }

    public List<OrderEvent> findEvents(long orderId) {
        return jdbc.query("SELECT * FROM order_event WHERE order_id = ? ORDER BY id",
                OrderEvent.rowMapper(), orderId);
    }

    /** 记录改派：原单保留、原油机释放、新油机在途。 */
    public void insertReassignment(long orderId, Long oldGenId, long newGenId, Instant at, String reason) {
        jdbc.update("""
                        INSERT INTO dispatch_reassignment (order_id, old_gen_id, new_gen_id, reassigned_at, reason)
                        VALUES (?, ?, ?, ?, ?)
                        """, orderId, oldGenId, newGenId, Timestamp.from(at), reason);
    }

    /** 查询事件关联的活动调度单（事件恢复时使用），不持锁。 */
    public Optional<DispatchOrder> findByEventAndActive(long eventId) {
        return jdbc.query("""
                        SELECT * FROM dispatch_order
                         WHERE event_id = ? AND status NOT IN ('RELEASED', 'CLOSED')
                         ORDER BY id
                         LIMIT 1
                        """, DispatchOrder.rowMapper(), eventId).stream().findFirst();
    }

    /** 按状态列出调度单（管理/测试用）。 */
    public List<DispatchOrder> findByStation(long stationId) {
        return jdbc.query("SELECT * FROM dispatch_order WHERE station_id = ? ORDER BY id",
                DispatchOrder.rowMapper(), stationId);
    }

    public List<DispatchOrder> findAll() {
        return jdbc.query("SELECT * FROM dispatch_order ORDER BY id", DispatchOrder.rowMapper());
    }
}
