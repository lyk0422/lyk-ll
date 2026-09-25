package com.example.starter.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.starter.support.AppClock;

/**
 * 发电车升级服务：特级用户停电满 5 分钟（恰满即判定）未恢复时启动发电车流程，每单仅触发一次。
 */
@Service
public class EscalationService {

    /** 特级用户复电时限：5 分钟。 */
    static final long SPECIAL_LIMIT_MINUTES = 5L;

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final WorkOrderStore store;

    public EscalationService(JdbcTemplate jdbc, AppClock clock, WorkOrderStore store) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.store = store;
    }

    /**
     * 评估单个工单是否触发发电车流程；已触发或已复电/封存/合并不再触发。
     *
     * @return true 表示本次调用触发了发电车流程
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean evaluate(long workOrderId) {
        return store.findById(workOrderId)
                .filter(o -> "SPECIAL".equals(o.importantLevel()))
                .filter(o -> switch (o.status()) {
                    case "PENDING", "QUEUED", "EXECUTING" -> true;
                    default -> false;
                })
                .filter(o -> !clock.now().isBefore(o.outageStartAt().plusMinutes(SPECIAL_LIMIT_MINUTES)))
                .map(o -> tryTrigger(o.id()))
                .orElse(false);
    }

    /**
     * 批量评估所有未终结的特级工单，返回新触发的工单ID列表。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public List<Long> evaluateAll() {
        List<Long> candidates = jdbc.query(
                "SELECT id FROM work_order WHERE important_level = 'SPECIAL'"
                + " AND status IN ('PENDING','QUEUED','EXECUTING')",
                (rs, rowNum) -> rs.getLong(1));
        return candidates.stream().filter(this::evaluate).toList();
    }

    private boolean tryTrigger(long workOrderId) {
        try {
            jdbc.update("INSERT INTO generator_event (work_order_id, triggered_at) VALUES (?,?)",
                    workOrderId, Timestamp.valueOf(clock.now()));
            store.addEvent(workOrderId, "GENERATOR_STARTED", "特级用户停电满5分钟未恢复, 启动发电车流程");
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    /**
     * 查询工单发电车流程启动时间，未启动返回 null。
     */
    public LocalDateTime generatorStartedAt(long workOrderId) {
        List<Timestamp> rows = jdbc.query(
                "SELECT triggered_at FROM generator_event WHERE work_order_id = ?",
                (rs, rowNum) -> rs.getTimestamp(1), workOrderId);
        return rows.isEmpty() ? null : rows.get(0).toLocalDateTime();
    }
}
