package com.example.starter.pressure;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.tank.TankService;
import com.example.starter.web.dto.PressureDetectRequest;
import com.example.starter.web.dto.PressureDetectResponse;
import com.example.starter.web.dto.TankRegisterRequest;
import com.example.starter.web.dto.TankView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压力联动 × 储罐闸控：绑定储罐的门站加注量先经可用量判定，
 * 不足时调压照常但加注不下发、不伪报加注成功；未绑定储罐的旧门站行为不变。
 */
class PressureTankIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDateTime T = LocalDate.of(2026, 9, 26).atTime(13, 0);

    @Autowired
    private PressureService pressureService;
    @Autowired
    private TankService tankService;

    private void registerTank(String tankCode, String station, String capacity,
                              String safety, String balance) {
        tankService.register(new TankRegisterRequest(station, tankCode, "REG-" + tankCode,
                new BigDecimal(capacity), new BigDecimal(safety), new BigDecimal(balance), T));
    }

    private PressureDetectResponse highPressure(String station, String reqId) {
        return pressureService.detect(new PressureDetectRequest(station, reqId, T,
                new BigDecimal("0.6000"), new BigDecimal("100")));
    }

    private void assertBd(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }

    private void assertBd(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                message + ": expected " + expected + " but was " + actual);
    }

    @Test
    void boundTankWithSufficientStockConsumesAndDispatches() {
        // 罐余额 1.000kg、安全储备 0.001kg：本次加注 100m3×20mg/m3=2000mg=0.002kg
        registerTank("TK-P1", "ST01", "1000", "0.001", "1.000");
        PressureDetectResponse resp = highPressure("ST01", "PT-1");

        assertEquals("HIGH", resp.grade());
        assertTrue(resp.accepted());
        assertTrue(resp.injectionDispatched(), "可用量充足须真实下发加注");
        assertBd("2000.000", resp.injectionDoseMg());
        assertBd("0.4000", resp.pressureAfterMpa());

        TankView view = tankService.tankView("TK-P1");
        assertBd("0.998", view.balanceKg(), "联动消耗 0.002kg 须扣减罐余额");
        assertEquals(2L, view.version());
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'CONSUME' "
                + "AND ref_type = 'PRESSURE' AND ref_id = ?", resp.taskId()));
        assertEquals(0, count("SELECT COUNT(*) FROM tank_refill_event"));
    }

    @Test
    void boundTankBelowSafetyBlocksInjectionButPressureProceeds() {
        // 罐余额 0.002kg、安全储备 0.001kg：需 0.002kg，扣除后可用量 0 低于安全储备
        registerTank("TK-P2", "ST01", "1000", "0.001", "0.002");
        PressureDetectResponse resp = highPressure("ST01", "PT-2");

        assertEquals("HIGH", resp.grade());
        assertTrue(resp.accepted(), "原压力处置仍可进行");
        assertFalse(resp.injectionDispatched(), "储罐不足不得伪报加注成功");
        assertBd("0", resp.injectionDoseMg());
        assertBd("20.000", resp.injectionRateAfterMgM3(), "加注速率维持不变");
        assertBd("0.4000", resp.pressureAfterMpa(), "调压照常回归目标压力");

        TankView view = tankService.tankView("TK-P2");
        assertBd("0.002", view.balanceKg(), "未下发不得扣减罐余额");
        assertEquals(1, count("SELECT COUNT(*) FROM tank_refill_event WHERE status = 'OPEN' "
                + "AND source = 'PRESSURE'"), "须生成待补液事件");
        assertEquals(0, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'CONSUME'"));
        assertEquals(1, count("SELECT COUNT(*) FROM linkage_task WHERE status = 'COMPLETED'"),
                "联动任务仍须完成（调压部分）");
    }

    @Test
    void unboundStationKeepsOldBehavior() {
        // ST02 未绑定储罐：行为不变，加注照常下发且不产生任何储罐数据
        PressureDetectResponse resp = pressureService.detect(new PressureDetectRequest(
                "ST02", "PT-3", T, new BigDecimal("0.6000"), new BigDecimal("100")));

        assertEquals("HIGH", resp.grade());
        assertTrue(resp.injectionDispatched());
        assertBd("2000.000", resp.injectionDoseMg());
        assertBd("20.000", resp.injectionRateAfterMgM3());
        assertEquals(0, count("SELECT COUNT(*) FROM tank"));
        assertEquals(0, count("SELECT COUNT(*) FROM tank_ledger"));
        assertEquals(0, count("SELECT COUNT(*) FROM tank_refill_event"));
    }
}
