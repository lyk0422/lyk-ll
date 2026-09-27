package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.pressure.PressureService;
import com.example.starter.web.dto.PressureDetectRequest;
import com.example.starter.web.dto.PressureDetectResponse;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压力联动与储罐计量集成：绑定储罐后联动加注先预占，
 * 低于安全储备时调压仍执行但加注不下发（不伪报成功）；未绑定门站维持旧行为。
 */
class PressureTankIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDateTime T = LocalDate.of(2026, 9, 26).atTime(13, 0);

    @Autowired
    private PressureService pressureService;
    @Autowired
    private TankService tankService;

    private void registerTank(String station, String requestId, String capacity,
                              String safety, String initial) {
        tankService.register(new TankRegisterRequest(station, requestId, "TK-" + requestId,
                new BigDecimal(capacity), new BigDecimal(safety), new BigDecimal(initial), T.minusHours(1)));
    }

    private PressureDetectRequest highPressure(String requestId, LocalDateTime time) {
        // 流量100m3 × 目标浓度20mg/m3 = 2000mg = 2kg 加注剂量
        return new PressureDetectRequest("ST01", requestId, time,
                new BigDecimal("0.6000"), new BigDecimal("100"));
    }

    @Test
    void unboundStationKeepsLegacyInjectionBehavior() {
        // ST01 不登记储罐：旧行为，2kg 加注按比例下发
        PressureDetectResponse resp = pressureService.detect(highPressure("PT-LEGACY", T));
        assertEquals("HIGH", resp.grade());
        assertTrue(resp.accepted());
        assertEquals(0, new BigDecimal("2000.000").compareTo(resp.injectionDoseMg()));
        assertFalse(resp.injectionBlockedByReserve());
        assertEquals(2, count("SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ?", resp.taskId()));
    }

    @Test
    void boundStationWithSufficientStockReservesAndConsumesAtomically() {
        registerTank("ST01", "PT-REG", "100.000", "20.000", "50.000");
        PressureDetectResponse resp = pressureService.detect(highPressure("PT-OK", T));
        assertTrue(resp.accepted());
        assertFalse(resp.injectionBlockedByReserve());
        assertEquals(0, new BigDecimal("2000.000").compareTo(resp.injectionDoseMg()));

        TankStatusResponse status = tankService.status("ST01");
        assertEquals(0, new BigDecimal("48.000").compareTo(status.balanceKg()), "余额50-2=48kg");
        assertEquals(0, new BigDecimal("0.000").compareTo(status.reservedKg()), "联动同事务结清预占");
        assertEquals(2, status.version(), "预占+消耗为一次成功联动，版本加1");
        assertEquals(1, count("SELECT COUNT(*) FROM injection_plan WHERE request_id = 'PT-OK' AND status = 'EXECUTED'"));
        assertEquals(2, count("SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ?", resp.taskId()),
                "加注实际下发时调压与加注两条指令同事务存在");
    }

    @Test
    void belowSafetyBlocksInjectionButPressureAdjustmentStillProceeds() {
        // 余额恰为安全储备20kg：再取2kg后可用18<20，加注被拦截
        registerTank("ST01", "PT-REG2", "100.000", "20.000", "20.000");
        PressureDetectResponse resp = pressureService.detect(highPressure("PT-BLOCK", T));
        assertEquals("HIGH", resp.grade());
        assertTrue(resp.accepted(), "联动任务仍受理");
        assertTrue(resp.injectionBlockedByReserve(), "加注被安全储备拦截");
        assertEquals(0, new BigDecimal("0.000").compareTo(resp.injectionDoseMg()),
                "不得伪报加注成功，剂量报0");
        assertEquals(0, new BigDecimal("0.4000").compareTo(resp.pressureAfterMpa()),
                "调压仍执行到目标压力");
        assertEquals(resp.injectionRateBeforeMgM3(), resp.injectionRateAfterMgM3(),
                "加注速率维持不变");

        TankStatusResponse status = tankService.status("ST01");
        assertEquals(0, new BigDecimal("20.000").compareTo(status.balanceKg()), "余额不变");
        assertEquals(0, new BigDecimal("0.000").compareTo(status.reservedKg()), "不留预占");
        assertEquals(1, status.version(), "拦截不变更版本");
        assertEquals(0, count("SELECT COUNT(*) FROM injection_plan WHERE request_id = 'PT-BLOCK'"),
                "被拦截不得生成加注计划");
        assertEquals(1, count("SELECT COUNT(*) FROM pending_replenish_event WHERE source_request_id = 'PT-BLOCK' "
                + "AND status = 'OPEN' AND source = 'PRESSURE'"), "生成压力来源待补液事件");
        assertEquals(1, count("SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ? AND issue_type = 'PRESSURE'",
                resp.taskId()), "调压指令照常下发");
        assertEquals(0, count("SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ? AND issue_type = 'INJECTION'",
                resp.taskId()), "加注指令不得下发");
    }
}
