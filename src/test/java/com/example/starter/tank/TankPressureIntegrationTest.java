package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.pressure.PressureService;
import com.example.starter.web.dto.PressureDetectRequest;
import com.example.starter.web.dto.PressureDetectResponse;
import com.example.starter.web.dto.TankRegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压力联动 × 储罐门禁：绑定储罐的门站加注先预占，储备不足时调压仍执行但
 * 不伪报加注成功；未绑定储罐的旧门站行为不变。
 */
class TankPressureIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDateTime T = LocalDate.of(2026, 9, 25).atTime(12, 0);

    @Autowired
    private TankService tankService;
    @Autowired
    private PressureService pressureService;

    private void register(String balance) {
        tankService.register(new TankRegisterRequest("ST01", "REG", new BigDecimal("1000"),
                new BigDecimal("5"), new BigDecimal(balance), T));
    }

    private PressureDetectResponse lowPressure(String stationCode, String requestId, String gasFlow) {
        // ST01 下限0.3000 / ST02 下限0.2000：0.1000 对两站均为 LOW
        return pressureService.detect(new PressureDetectRequest(stationCode, requestId,
                T, new BigDecimal("0.1000"), new BigDecimal(gasFlow)));
    }

    @Test
    void linkageDispatchesAndReservesWhenReserveSufficient() {
        register("500");
        // 流量100000 m3 × 20 mg/m3 = 2,000,000 mg = 2 kg
        PressureDetectResponse resp = lowPressure("ST01", "P-1", "100000");
        assertTrue(resp.accepted());
        assertDecimalEquals("2000000", resp.injectionDoseMg());
        assertDecimalEquals("20", resp.injectionRateAfterMgM3());

        // 联动计划已预占 2 kg，等待回执
        assertEquals(1, count("SELECT COUNT(*) FROM injection_plan WHERE source = 'LINKAGE' "
                + "AND status = 'PREOCCUPIED' AND task_id = " + resp.taskId()));
        assertDecimalEquals("2", decimal("SELECT reserved_kg FROM odorant_tank WHERE station_id = 1"));
        assertEquals(0, count("SELECT COUNT(*) FROM refill_event"));
    }

    @Test
    void linkageBlockedByReserveStillAdjustsPressureWithoutFakingInjection() {
        // 余额6、安全储备5：预占2kg后可用4 < 5 → 不下发加注
        register("6");
        PressureDetectResponse resp = lowPressure("ST01", "P-2", "100000");
        assertTrue(resp.accepted(), "压力联动任务仍受理");
        // 调压处置仍进行
        assertDecimalEquals("0.4000", resp.pressureAfterMpa());
        // 不伪报加注成功：加注量记0、速率维持
        assertDecimalEquals("0", resp.injectionDoseMg());
        assertDecimalEquals(resp.injectionRateBeforeMgM3().toPlainString(),
                resp.injectionRateAfterMgM3());

        // 生成待补液事件，计划记 REJECTED，储罐未预占
        assertEquals(1, count("SELECT COUNT(*) FROM refill_event WHERE status = 'PENDING'"));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT status FROM injection_plan WHERE source = 'LINKAGE'", String.class));
        assertDecimalEquals("0", decimal("SELECT reserved_kg FROM odorant_tank WHERE station_id = 1"));
        // 调压指令仍下发；加注指令以维持（前后相同）留证
        assertEquals(1, count("SELECT COUNT(*) FROM adjustment_issue WHERE issue_type = 'PRESSURE'"));
        assertEquals(1, count("SELECT COUNT(*) FROM adjustment_issue WHERE issue_type = 'INJECTION' "
                + "AND before_value = after_value"));
    }

    @Test
    void unboundStationKeepsLegacyBehavior() {
        // ST02 未绑定储罐：加注照常下发，不产生任何储罐侧记录
        PressureDetectResponse resp = lowPressure("ST02", "P-3", "100000");
        assertTrue(resp.accepted());
        assertDecimalEquals("2000000", resp.injectionDoseMg());
        assertEquals(0, count("SELECT COUNT(*) FROM injection_plan"));
        assertEquals(0, count("SELECT COUNT(*) FROM refill_event"));
        assertEquals(0, count("SELECT COUNT(*) FROM odorant_tank"));
    }
}
