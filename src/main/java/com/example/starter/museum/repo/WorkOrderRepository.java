package com.example.starter.museum.repo;

import com.example.starter.museum.domain.po.ProtectionWorkOrderPo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** 保护工单仓储。alert_id 唯一：一次异常至多一张工单。 */
@Repository
public class WorkOrderRepository {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WorkOrderRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Long insert(ProtectionWorkOrderPo po) {
        jdbc.update("""
                INSERT INTO protection_work_order
                  (order_no, alert_id, showcase_id, status, deadline, confirmed_at,
                   handler, note, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, po.orderNo, po.alertId, po.showcaseId, po.status.name(),
                Sql.ts(po.deadline), Sql.ts(po.confirmedAt), po.handler, po.note,
                Sql.ts(po.createdAt));
        return jdbc.queryForObject("SELECT id FROM protection_work_order WHERE order_no = ?",
                Long.class, po.orderNo);
    }

    public Optional<ProtectionWorkOrderPo> findByNo(String orderNo) {
        return jdbc.query("SELECT * FROM protection_work_order WHERE order_no = ?",
                RowMappers.WORK_ORDER, orderNo).stream().findFirst();
    }

    public ProtectionWorkOrderPo getByNo(String orderNo) {
        return findByNo(orderNo).orElseThrow(() -> new IllegalArgumentException("保护工单不存在: " + orderNo));
    }

    public Optional<ProtectionWorkOrderPo> findByAlert(Long alertId) {
        return jdbc.query("SELECT * FROM protection_work_order WHERE alert_id = ?",
                RowMappers.WORK_ORDER, alertId).stream().findFirst();
    }

    public List<ProtectionWorkOrderPo> findByShowcase(Long showcaseId) {
        return jdbc.query(
                "SELECT * FROM protection_work_order WHERE showcase_id = ? ORDER BY id",
                RowMappers.WORK_ORDER, showcaseId);
    }

    /** 条件确认：仅 PENDING 可确认，返回是否生效（并发/重复确认安全）。 */
    public int confirmIfPending(Long id, String handler, String note) {
        return jdbc.update("""
                UPDATE protection_work_order
                SET status = 'CONFIRMED', confirmed_at = ?, handler = ?, note = ?
                WHERE id = ? AND status = 'PENDING'
                """, Sql.ts(clock.instant()), handler, note, id);
    }

    public List<ProtectionWorkOrderPo> findPending() {
        return jdbc.query(
                "SELECT * FROM protection_work_order WHERE status = 'PENDING' ORDER BY id",
                RowMappers.WORK_ORDER);
    }
}
