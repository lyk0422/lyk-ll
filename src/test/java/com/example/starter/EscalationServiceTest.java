package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.DispatchService;
import com.example.starter.service.EscalationService;
import com.example.starter.service.FaultReportService;
import com.example.starter.service.RestoreService;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.RestoreConfirmRequest;

/**
 * 发电车升级：特级用户停电恰满 5 分钟未恢复即触发，且仅触发一次；已复电或非特级不触发。
 */
class EscalationServiceTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private FaultReportService reports;

    @Autowired
    private DispatchService dispatch;

    @Autowired
    private RestoreService restore;

    @Autowired
    private EscalationService escalation;

    private long specialOrder() {
        return reports.report(new FaultReportRequest(
                "g-1", "F01", "医院专线#1", 30, "市一医院", T0)).workOrderId();
    }

    @Test
    void 特级恰满5分钟未恢复即启动发电车流程() {
        freezeAt(T0);
        long order = specialOrder();
        dispatch.dispatch(new DispatchRequest("g-d-1", order, teamId("抢修一班")));

        freezeAt(T0.plusMinutes(4).plusSeconds(59));
        assertFalse(escalation.evaluate(order), "未满5分钟不应触发");
        assertNull(escalation.generatorStartedAt(order));

        freezeAt(T0.plusMinutes(5));
        assertTrue(escalation.evaluate(order), "恰满5分钟应触发");
        assertEquals(T0.plusMinutes(5), escalation.generatorStartedAt(order));

        assertFalse(escalation.evaluate(order), "重复评估不应再次触发");
        assertEquals(1, count("generator_event", "work_order_id = ?", order));
    }

    @Test
    void 已复电工单不再触发发电车() {
        freezeAt(T0);
        long order = specialOrder();
        dispatch.dispatch(new DispatchRequest("g-d-2", order, teamId("抢修一班")));
        freezeAt(T0.plusMinutes(3));
        restore.confirm(new RestoreConfirmRequest("g-c-1", order, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("g-c-2", order, "FIELD", "现场员乙",
                new BigDecimal("300")));
        freezeAt(T0.plusMinutes(6));
        assertFalse(escalation.evaluate(order));
        assertEquals(0, count("generator_event", "work_order_id = ?", order));
    }

    @Test
    void 非特级工单不触发发电车() {
        freezeAt(T0);
        long order = reports.report(new FaultReportRequest(
                "g-2", "F02", "普通台区", 500, null, T0)).workOrderId();
        freezeAt(T0.plusMinutes(30));
        assertFalse(escalation.evaluate(order));
        assertEquals(0, count("generator_event", "work_order_id = ?", order));
    }
}
