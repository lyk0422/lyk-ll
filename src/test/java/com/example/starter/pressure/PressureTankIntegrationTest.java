package com.example.starter.pressure;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.tank.InjectionPlan;
import com.example.starter.tank.RefillEvent;
import com.example.starter.tank.TankFixtures;
import com.example.starter.tank.TankService;
import com.example.starter.web.dto.PressureDetectRequest;
import com.example.starter.web.dto.PressureDetectResponse;
import com.example.starter.web.dto.RefillConfirmRequest;
import com.example.starter.web.dto.TankStatusResponse;
import com.example.starter.tank.RefillService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压力联动 × 储罐安全储备：绑定储罐的门站加注先预占，触及安全储备则
 * 生成待补液事件、不下发加注但调压处置仍完成；未绑定门站行为不变。
 */
class PressureTankIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDateTime T = LocalDate.of(2026, 9, 26).atTime(12, 0);

    @Autowired
    private PressureService pressureService;
    @Autowired
    private TankService tankService;
    @Autowired
    private RefillService refillService;

    private PressureDetectResponse detectLow(String requestId, BigDecimal gasFlowM3) {
        // ST01 下限 0.3000：0.2000 为 LOW 触发联动
        return pressureService.detect(new PressureDetectRequest("ST01", requestId,
                T, new BigDecimal("0.2000"), gasFlowM3));
    }

    @Test
    void sufficientTankReservesAndDispatchesInjection() {
        TankFixtures.registerStandard(tankService);

        // 流量100m3 × 20mg/m3 = 2000mg = 0.002kg，可用50远高于安全储备10
        PressureDetectResponse resp = detectLow("PT-1", new BigDecimal("100"));

        assertTrue(resp.accepted());
        assertFalse(resp.injectionWithheld());
        assertNotNull(resp.injectionPlanId(), "绑定储罐时加注须先预占");
        assertNull(resp.refillEventId());
        assertEquals(0, resp.injectionDoseMg().compareTo(new BigDecimal("2000.000")));
        assertEquals(0, resp.injectionRateAfterMgM3().compareTo(new BigDecimal("20.000")));

        TankStatusResponse status = tankService.status("ST01");
        assertEquals(0, status.reservedKg().compareTo(new BigDecimal("0.002")));
        assertEquals(0, status.balanceKg().compareTo(new BigDecimal("50.000")), "预占不减余额");
        assertEquals(2, status.version());

        List<InjectionPlan> plans = tankService.plans("ST01");
        assertEquals(1, plans.size());
        assertEquals("RESERVED", plans.get(0).status());
        assertEquals(resp.taskId(), plans.get(0).taskId());
        assertEquals(2, count("SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ?",
                resp.taskId()), "调压与加注指令均下发");
    }

    @Test
    void insufficientTankWithholdsInjectionButPressureAdjusts() {
        // 余额恰等安全储备10：任何预占都会击穿安全储备
        TankFixtures.register(tankService, "ST01",
                new BigDecimal("100"), new BigDecimal("10"), new BigDecimal("10"));

        PressureDetectResponse resp = detectLow("PT-2", new BigDecimal("100"));

        assertTrue(resp.accepted(), "压力处置仍受理");
        assertTrue(resp.injectionWithheld(), "触及安全储备不得伪报加注成功");
        assertNull(resp.injectionPlanId());
        assertNotNull(resp.refillEventId(), "生成待补液事件");
        assertEquals(0, resp.injectionDoseMg().compareTo(BigDecimal.ZERO.setScale(3)),
                "未下发加注，加注量为0");
        assertEquals(0, resp.pressureAfterMpa().compareTo(new BigDecimal("0.4000")),
                "调压处置仍完成");
        assertEquals(0, resp.injectionRateAfterMgM3().compareTo(resp.injectionRateBeforeMgM3()),
                "加注速率维持不变");

        // 只下发调压指令，未下发加注指令
        assertEquals(1, count("SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ? "
                + "AND issue_type = 'PRESSURE'", resp.taskId()));
        assertEquals(0, count("SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ? "
                + "AND issue_type = 'INJECTION'", resp.taskId()));
        assertEquals("COMPLETED", jdbc.queryForObject(
                "SELECT status FROM linkage_task WHERE id = ?", String.class, resp.taskId()));

        // 储罐未被扣占，事件缺口 = 10 - (10 - 0.002) = 0.002
        TankStatusResponse status = tankService.status("ST01");
        assertEquals(0, status.reservedKg().compareTo(BigDecimal.ZERO.setScale(3)));
        assertEquals(1, status.version());
        List<RefillEvent> events = tankService.refillEvents("ST01");
        assertEquals(1, events.size());
        assertEquals("PRESSURE", events.get(0).source());
        assertEquals(0, events.get(0).deficitKg().compareTo(new BigDecimal("0.002")));

        // 补液后事件关闭，但不自动重发旧加注命令
        refillService.confirm(new RefillConfirmRequest("ST01", "PT-REFILL", "BATCH-PT",
                new BigDecimal("20"), new BigDecimal("20.000"), "OP-A", "OP-B",
                T.plusHours(2), 1));
        assertEquals("CLOSED", tankService.refillEvents("ST01").get(0).status());
        assertEquals(0, count("SELECT COUNT(*) FROM injection_plan"),
                "不因一次入账自动重发旧加注命令");
        assertEquals(0, count("SELECT COUNT(*) FROM adjustment_issue "
                + "WHERE issue_type = 'INJECTION'"));
    }

    @Test
    void unboundStationKeepsOldBehavior() {
        // ST02 未绑定储罐：行为不变，无预占无事件
        PressureDetectResponse resp = pressureService.detect(new PressureDetectRequest(
                "ST02", "PT-3", T, new BigDecimal("0.1000"), new BigDecimal("100")));

        assertTrue(resp.accepted());
        assertFalse(resp.injectionWithheld());
        assertNull(resp.injectionPlanId());
        assertNull(resp.refillEventId());
        assertEquals(0, resp.injectionDoseMg().compareTo(new BigDecimal("2000.000")));
        assertEquals(0, count("SELECT COUNT(*) FROM injection_plan"));
        assertEquals(0, count("SELECT COUNT(*) FROM refill_event"));
    }
}
