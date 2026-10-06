package com.example.starter.service;

import java.time.Clock;
import java.time.LocalDateTime;

import com.example.starter.domain.DefectStatus;
import com.example.starter.domain.RepairOrderStatus;
import com.example.starter.domain.Rows.DefectRow;
import com.example.starter.domain.Rows.RepairOrderRow;
import com.example.starter.error.BusinessConflictException;
import com.example.starter.idem.IdempotencyService;
import com.example.starter.web.dto.ApiDtos.AddRepairRecordRequest;
import com.example.starter.web.dto.ApiDtos.CreateRepairOrderRequest;
import com.example.starter.web.dto.ApiDtos.ReinspectRequest;
import com.example.starter.web.dto.ApiDtos.ReinspectResponse;
import com.example.starter.web.dto.ApiDtos.RepairOrderResponse;
import com.example.starter.web.dto.ApiDtos.RepairRecordResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

/**
 * 维修单、维修记录与复检：复检合格时在同一事务内结案、闭环缺陷并撤销限功率；
 * 复检不合格则维持限功率且维修单不得结案；逾期闭环先落升级告警。
 */
@Service
public class RepairService {

    private final JdbcClient jdbc;
    private final DbSupport db;
    private final EscalationService escalationService;
    private final IdempotencyService idempotencyService;
    private final Clock clock;

    public RepairService(JdbcClient jdbc, DbSupport db, EscalationService escalationService,
                         IdempotencyService idempotencyService, Clock clock) {
        this.jdbc = jdbc;
        this.db = db;
        this.escalationService = escalationService;
        this.idempotencyService = idempotencyService;
        this.clock = clock;
    }

    /**
     * 创建维修单：期限 = 缺陷首次登记 + 7 日；同叶片同时刻仅允许一张进行中维修单。
     */
    public RepairOrderResponse createOrder(CreateRepairOrderRequest req) {
        String fingerprint = IdempotencyService.fingerprint(
                "repair-order", req.requestId(), String.valueOf(req.defectId()));
        return idempotencyService.execute("repair-order", req.requestId(), fingerprint,
                () -> doCreateOrder(req), RepairOrderResponse.class);
    }

    /**
     * 追加维修记录：仅进行中维修单可追加；维修记录独立成行，不覆盖原缺陷。
     */
    public RepairRecordResponse addRepairRecord(long orderId, AddRepairRecordRequest req) {
        String fingerprint = IdempotencyService.fingerprint(
                "repair-record", req.requestId(), String.valueOf(orderId),
                req.technician(), req.description());
        return idempotencyService.execute("repair-record", req.requestId(), fingerprint,
                () -> doAddRepairRecord(orderId, req), RepairRecordResponse.class);
    }

    /**
     * 复检登记：PASS 则同事务结案 + 闭环缺陷 + 撤销结构级限功率；FAIL 则维持现状。
     */
    public ReinspectResponse reinspect(long orderId, ReinspectRequest req) {
        String fingerprint = IdempotencyService.fingerprint(
                "reinspection", req.requestId(), String.valueOf(orderId),
                req.result(), req.inspector());
        return idempotencyService.execute("reinspection", req.requestId(), fingerprint,
                () -> doReinspect(orderId, req), ReinspectResponse.class);
    }

    private RepairOrderResponse doCreateOrder(CreateRepairOrderRequest req) {
        DefectRow defect = db.requireDefect(req.defectId());
        if (defect.status() == DefectStatus.CLOSED) {
            throw new BusinessConflictException("缺陷已闭环，不能再建维修单: " + req.defectId());
        }
        long openForDefect = jdbc.sql("""
                SELECT COUNT(*) FROM repair_order WHERE defect_id = ? AND status = 'IN_PROGRESS'
                """)
                .param(req.defectId())
                .query(Long.class)
                .single();
        if (openForDefect > 0) {
            throw new BusinessConflictException("该缺陷已有进行中维修单: " + req.defectId());
        }
        long openForBlade = jdbc.sql("""
                SELECT COUNT(*) FROM repair_order WHERE blade_id = ? AND status = 'IN_PROGRESS'
                """)
                .param(defect.bladeId())
                .query(Long.class)
                .single();
        if (openForBlade > 0) {
            throw new BusinessConflictException("同一叶片同时刻只允许一张进行中维修单: " + defect.bladeId());
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime deadline = defect.registeredAt().plusDays(EscalationService.REPAIR_DEADLINE_DAYS);
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.sql("""
                            INSERT INTO repair_order (request_id, defect_id, blade_id, status, deadline_at, created_at, active_blade_key)
                            VALUES (?, ?, ?, 'IN_PROGRESS', ?, ?, ?)
                            """)
                    .params(req.requestId(), req.defectId(), defect.bladeId(), deadline, now, defect.bladeId())
                    .update(keys);
        } catch (DuplicateKeyException e) {
            // 并发下同叶片第二张进行中单被唯一约束拦截，事务回滚
            throw new BusinessConflictException("同一叶片同时刻只允许一张进行中维修单: " + defect.bladeId());
        }
        long id = keys.getKey().longValue();
        return new RepairOrderResponse(id, req.defectId(), defect.bladeId(),
                RepairOrderStatus.IN_PROGRESS.name(), deadline, now, null);
    }

    private RepairRecordResponse doAddRepairRecord(long orderId, AddRepairRecordRequest req) {
        RepairOrderRow order = db.requireOrder(orderId);
        if (order.status() != RepairOrderStatus.IN_PROGRESS) {
            throw new BusinessConflictException("维修单已结案，不能追加维修记录: " + orderId);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO repair_record (request_id, repair_order_id, technician, description, repaired_at)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(req.requestId(), orderId, req.technician(), req.description(), now)
                .update(keys);
        long id = keys.getKey().longValue();
        return new RepairRecordResponse(id, orderId, req.technician(), req.description(), now);
    }

    private ReinspectResponse doReinspect(long orderId, ReinspectRequest req) {
        RepairOrderRow order = db.requireOrder(orderId);
        if (order.status() != RepairOrderStatus.IN_PROGRESS) {
            throw new BusinessConflictException("维修单已结案，不能重复复检: " + orderId);
        }
        DefectRow defect = db.requireDefect(order.defectId());
        LocalDateTime now = LocalDateTime.now(clock);

        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO reinspection_record (request_id, repair_order_id, defect_id, result, inspector, inspected_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """)
                .params(req.requestId(), orderId, order.defectId(), req.result(), req.inspector(), now)
                .update(keys);
        long reinspectId = keys.getKey().longValue();

        if ("FAIL".equals(req.result())) {
            // 复检不合格：继续限功率，维修单不得结案
            return new ReinspectResponse(reinspectId, orderId, req.result(),
                    RepairOrderStatus.IN_PROGRESS.name(), defect.status().name(), false, false);
        }

        // 复检合格：逾期先升级（保留首次登记时刻与告警），再同事务结案、闭环、撤销限功率
        boolean escalated = escalationService.ensureEscalationIfOverdue(defect, now);
        jdbc.sql("""
                UPDATE repair_order SET status = 'CLOSED', closed_at = ?, active_blade_key = NULL WHERE id = ?
                """)
                .params(now, orderId)
                .update();
        jdbc.sql("UPDATE defect SET status = 'CLOSED', closed_at = ? WHERE id = ?")
                .params(now, order.defectId())
                .update();
        int lifted = jdbc.sql("""
                UPDATE curtailment SET lifted_at = ?
                WHERE source_defect_id = ? AND lifted_at IS NULL
                """)
                .params(now, order.defectId())
                .update();
        return new ReinspectResponse(reinspectId, orderId, req.result(),
                RepairOrderStatus.CLOSED.name(), DefectStatus.CLOSED.name(), lifted > 0, escalated);
    }
}
