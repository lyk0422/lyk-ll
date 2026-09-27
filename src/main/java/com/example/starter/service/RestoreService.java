package com.example.starter.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.starter.support.AppClock;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.RestoreConfirmRequest;
import com.example.starter.web.dto.RestoreConfirmResponse;

/**
 * 复电确认服务：调度与现场双确认方生效，复电确认与负荷恢复记录同事务完成；
 * 复电后释放班组并自动提升挂单；封存后迟到回报只记迟到事件。
 */
@Service
public class RestoreService {

    private final JdbcTemplate jdbc;
    private final AppClock clock;
    private final WorkOrderStore store;
    private final GeneratorDispatchService generatorDispatches;
    private final TransactionTemplate tx;

    public RestoreService(JdbcTemplate jdbc, AppClock clock, WorkOrderStore store,
            GeneratorDispatchService generatorDispatches, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.store = store;
        this.generatorDispatches = generatorDispatches;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * 复电确认：同 requestId 同参返回首次结果，同键异参抛 409；单人不生效，双角色齐全才复电。
     */
    public RestoreConfirmResponse confirm(RestoreConfirmRequest req) {
        if (!"DISPATCHER".equals(req.role()) && !"FIELD".equals(req.role())) {
            throw new IllegalArgumentException("非法确认角色: " + req.role());
        }
        if ("FIELD".equals(req.role()) && (req.loadKw() == null || req.loadKw().signum() <= 0)) {
            throw new IllegalArgumentException("现场确认必须携带正的恢复负荷 loadKw");
        }
        String fingerprint = Fingerprints.of(req.requestId(), req.workOrderId(), req.role(),
                req.confirmer(), req.loadKw());
        try {
            return tx.execute(status -> doConfirm(req, fingerprint));
        } catch (DuplicateKeyException e) {
            return tx.execute(status -> findReplay(req.requestId(), fingerprint)
                    .orElseThrow(() -> e));
        }
    }

    private RestoreConfirmResponse doConfirm(RestoreConfirmRequest req, String fingerprint) {
        Optional<RestoreConfirmResponse> replay = findReplay(req.requestId(), fingerprint);
        if (replay.isPresent()) {
            return replay.get();
        }
        WorkOrderRow order = store.requireById(req.workOrderId());
        if ("SEALED".equals(order.status())) {
            // 封存为终态：迟到回报只记迟到事件，不改变工单状态
            insertConfirmation(req, fingerprint);
            jdbc.update("INSERT INTO late_report_event (work_order_id, event_type, detail, created_at)"
                    + " VALUES (?,?,?,?)",
                    req.workOrderId(), "LATE_RESTORE_CONFIRM",
                    "封存后迟到复电确认, 角色=" + req.role() + ", 确认人=" + req.confirmer(),
                    Timestamp.valueOf(clock.now()));
            return new RestoreConfirmResponse(req.workOrderId(), false, true, "SEALED", false);
        }
        if (!"EXECUTING".equals(order.status())) {
            throw new ConflictException("工单状态不可复电确认: " + order.status());
        }
        List<String> priorConfirmers = jdbc.query(
                "SELECT confirmer FROM restore_confirmation WHERE work_order_id = ?",
                (rs, rowNum) -> rs.getString(1), req.workOrderId());
        if (priorConfirmers.contains(req.confirmer())) {
            throw new ConflictException("同一确认人不可重复确认（单人不生效）: " + req.confirmer());
        }
        List<String> priorRoles = jdbc.query(
                "SELECT DISTINCT confirmer_role FROM restore_confirmation WHERE work_order_id = ?",
                (rs, rowNum) -> rs.getString(1), req.workOrderId());
        if (priorRoles.contains(req.role())) {
            throw new ConflictException("角色已确认过: " + req.role());
        }
        insertConfirmation(req, fingerprint);
        priorRoles.add(req.role());
        if (!(priorRoles.contains("DISPATCHER") && priorRoles.contains("FIELD"))) {
            return new RestoreConfirmResponse(req.workOrderId(), false, false, "EXECUTING", false);
        }
        // 双确认齐全：复电与负荷恢复记录在同一事务内完成
        BigDecimal loadKw = jdbc.queryForObject(
                "SELECT load_kw FROM restore_confirmation WHERE work_order_id = ? AND confirmer_role = 'FIELD'",
                BigDecimal.class, req.workOrderId());
        jdbc.update("UPDATE work_order SET status = 'RESTORED', restored_at = ?, executing_team_id = NULL,"
                + " version = version + 1 WHERE id = ?", Timestamp.valueOf(clock.now()), req.workOrderId());
        jdbc.update("INSERT INTO load_recovery (work_order_id, load_kw, recorded_at) VALUES (?,?,?)",
                req.workOrderId(), loadKw, Timestamp.valueOf(clock.now()));
        store.addEvent(req.workOrderId(), "RESTORED", "调度与现场双确认复电, 恢复负荷" + loadKw + "kW");
        // 正式复电：原子撤销未结束发电车调度并释放车辆，供电证据保留
        generatorDispatches.cancelForWorkOrder(req.workOrderId(), "正式复电");
        promoteQueued(order.teamId());
        return new RestoreConfirmResponse(req.workOrderId(), true, false, "RESTORED", false);
    }

    /**
     * 班组释放后，将该班组挂单队列中优先级最高的工单提升为执行中。
     */
    private void promoteQueued(Long teamId) {
        if (teamId == null) {
            return;
        }
        List<Long> queued = jdbc.query(
                "SELECT id FROM work_order WHERE status = 'QUEUED' AND team_id = ?"
                + WorkOrderStore.PRIORITY_ORDER + " LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), teamId);
        if (!queued.isEmpty()) {
            long next = queued.get(0);
            jdbc.update("UPDATE work_order SET status = 'EXECUTING', executing_team_id = ?,"
                    + " version = version + 1 WHERE id = ?", teamId, next);
            store.addEvent(next, "DISPATCHED", "班组" + teamId + "释放, 挂单自动提升执行");
        }
    }

    private void insertConfirmation(RestoreConfirmRequest req, String fingerprint) {
        jdbc.update("INSERT INTO restore_confirmation (request_id, fingerprint, work_order_id,"
                + " confirmer_role, confirmer, load_kw, created_at) VALUES (?,?,?,?,?,?,?)",
                req.requestId(), fingerprint, req.workOrderId(), req.role(), req.confirmer(),
                req.loadKw(), Timestamp.valueOf(clock.now()));
    }

    private Optional<RestoreConfirmResponse> findReplay(String requestId, String fingerprint) {
        List<Object[]> rows = jdbc.query(
                "SELECT fingerprint, work_order_id FROM restore_confirmation WHERE request_id = ?",
                (rs, rowNum) -> new Object[] {rs.getString(1), rs.getLong(2)}, requestId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        if (!row[0].equals(fingerprint)) {
            throw new ConflictException("同键异参幂等冲突: requestId=" + requestId);
        }
        long workOrderId = (Long) row[1];
        WorkOrderRow order = store.requireById(workOrderId);
        boolean late = !jdbc.query(
                "SELECT 1 FROM late_report_event WHERE work_order_id = ? LIMIT 1",
                (rs, rowNum) -> 1, workOrderId).isEmpty()
                && "SEALED".equals(order.status());
        return Optional.of(new RestoreConfirmResponse(workOrderId, "RESTORED".equals(order.status()),
                late, order.status(), true));
    }
}
