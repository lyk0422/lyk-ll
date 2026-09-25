package com.example.starter.flow;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.close.DailyCloseService;
import com.example.starter.odorant.OdorantService;
import com.example.starter.pressure.PressureService;
import com.example.starter.query.CurvePoint;
import com.example.starter.query.PressureChainPoint;
import com.example.starter.query.ReconciliationResult;
import com.example.starter.query.RecomputeService;
import com.example.starter.valve.LockService;
import com.example.starter.valve.ValveStateRepository;
import com.example.starter.web.dto.DailyCloseRequest;
import com.example.starter.web.dto.DailyCloseResponse;
import com.example.starter.web.dto.LockConfirmRequest;
import com.example.starter.web.dto.LockConfirmResponse;
import com.example.starter.web.dto.OdorantDetectRequest;
import com.example.starter.web.dto.OdorantDetectResponse;
import com.example.starter.web.dto.PressureDetectRequest;
import com.example.starter.web.dto.PressureDetectResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端合成示例：运行监测(连续越限锁阀) → 异常处置(双人复开) →
 * 压力调节(超上限联动) → 日结(勾稽恰3%封存) → 封存后迟到只记事件 → 三链路复算。
 */
class FullFlowIntegrationTest extends AbstractIntegrationTest {

    private static final LocalDate BIZ_DATE = LocalDate.of(2026, 9, 25);

    @Autowired
    private OdorantService odorantService;
    @Autowired
    private LockService lockService;
    @Autowired
    private PressureService pressureService;
    @Autowired
    private DailyCloseService dailyCloseService;
    @Autowired
    private RecomputeService recomputeService;
    @Autowired
    private ValveStateRepository valveStateRepository;

    @Test
    void breachLockReopenPressureAdjustDailyCloseAndRecompute() {
        // ① 浓度连续 2 次越限（15.900、15.800 均低于闭区间下限16.000），每次流量100m3
        OdorantDetectResponse first = odorantService.detect(new OdorantDetectRequest("ST01", "E2E-O1",
                BIZ_DATE.atTime(8, 0), new BigDecimal("100"), new BigDecimal("15.900")));
        assertEquals(1, first.breachCount());
        assertFalse(first.injectionLocked());

        OdorantDetectResponse second = odorantService.detect(new OdorantDetectRequest("ST01", "E2E-O2",
                BIZ_DATE.atTime(9, 0), new BigDecimal("100"), new BigDecimal("15.800")));
        assertEquals(2, second.breachCount());
        assertTrue(second.injectionLocked(), "恰连续2次越限锁定加注阀");
        assertTrue(second.alarmActive());

        // 单人复开不生效
        LockConfirmResponse single = lockService.confirm(
                new LockConfirmRequest("ST01", "E2E-L1", "REOPEN", "U001", "U001"));
        assertFalse(single.effective());
        assertTrue(valveStateRepository.find(1L).injectionLocked());

        // ② 双人复开生效，告警同事务解除，计数归零
        LockConfirmResponse reopened = lockService.confirm(
                new LockConfirmRequest("ST01", "E2E-L2", "REOPEN", "U001", "U002"));
        assertTrue(reopened.effective());
        assertFalse(reopened.injectionLocked());
        assertFalse(reopened.alarmActive());

        // ③ 压力 0.6000 超上限 0.5000，联动调压到 0.4000 并按比例加注 100×20=2000mg
        PressureDetectResponse pressure = pressureService.detect(new PressureDetectRequest("ST01", "E2E-P1",
                BIZ_DATE.atTime(10, 0), new BigDecimal("0.6000"), new BigDecimal("100")));
        assertEquals("HIGH", pressure.grade());
        assertTrue(pressure.accepted());
        assertEquals(0, new BigDecimal("0.4000").compareTo(pressure.pressureAfterMpa()));
        assertEquals(0, new BigDecimal("2000.000").compareTo(pressure.injectionDoseMg()));
        assertEquals(2, count("SELECT COUNT(*) FROM adjustment_issue WHERE task_id = ?", pressure.taskId()),
                "调压与加注两条指令同事务下发");

        // ④ 日结：有效检测流量 200m3（两次浓度检测），理论消耗 4000mg；恰+3%=4120mg 封存
        DailyCloseResponse close = dailyCloseService.close(
                new DailyCloseRequest("ST01", "E2E-D1", BIZ_DATE, new BigDecimal("4120")));
        assertEquals(0, new BigDecimal("3.0000").compareTo(close.deviationPct()));
        assertEquals("SEALED", close.status());
        assertTrue(close.sealed());

        // ⑤ 封存后迟到检测只记迟到事件
        OdorantDetectResponse late = odorantService.detect(new OdorantDetectRequest("ST01", "E2E-LATE",
                BIZ_DATE.atTime(23, 59), new BigDecimal("999"), new BigDecimal("15.000")));
        assertTrue(late.late());
        assertEquals(0, count("SELECT COUNT(*) FROM odorant_reading WHERE request_id = 'E2E-LATE'"));
        assertEquals(1, count("SELECT COUNT(*) FROM late_event WHERE request_id = 'E2E-LATE'"));

        // ⑥ 三链路复算：浓度曲线2点、压力链1点、勾稽仍为封存时口径（迟到流量不入账）
        List<CurvePoint> curve = recomputeService.concentrationCurve("ST01", BIZ_DATE);
        assertEquals(2, curve.size(), "迟到数据不进入浓度曲线");
        assertFalse(curve.get(0).qualified());
        assertFalse(curve.get(1).qualified());

        List<PressureChainPoint> chain = recomputeService.pressureChain("ST01", BIZ_DATE);
        assertEquals(1, chain.size());
        assertEquals("HIGH", chain.get(0).grade());
        assertTrue(chain.get(0).pressureAdjusted());
        assertTrue(chain.get(0).injectionAdjusted());

        ReconciliationResult reconciliation = recomputeService.reconcile("ST01", BIZ_DATE);
        assertEquals(0, new BigDecimal("200.000").compareTo(reconciliation.totalFlowM3()));
        assertEquals(0, new BigDecimal("4000.000").compareTo(reconciliation.expectedOdorantMg()));
        assertEquals(0, new BigDecimal("3.0000").compareTo(reconciliation.deviationPct()));
        assertTrue(reconciliation.sealed());
    }
}
