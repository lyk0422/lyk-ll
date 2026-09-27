package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.close.DailyCloseService;
import com.example.starter.odorant.OdorantService;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.dto.DailyCloseRequest;
import com.example.starter.web.dto.InjectionPlanRequest;
import com.example.starter.web.dto.InjectionPlanResponse;
import com.example.starter.web.dto.OdorantDetectRequest;
import com.example.starter.web.dto.RefillConfirmRequest;
import com.example.starter.web.dto.RefillConfirmResponse;
import com.example.starter.web.dto.TankStatusResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 补液交接：双人门禁、浓度许可闭区间、容量上限、唯一运输批号、
 * 原子入账关闭可满足事件、不自动重发旧加注命令、日结后补液不改旧勾稽。
 */
class RefillServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TankService tankService;
    @Autowired
    private InjectionPlanService planService;
    @Autowired
    private RefillService refillService;
    @Autowired
    private OdorantService odorantService;
    @Autowired
    private DailyCloseService dailyCloseService;

    private RefillConfirmResponse refill(String batchNo, BigDecimal quantity,
                                         BigDecimal concentration, int expectedVersion) {
        return refillService.confirm(new RefillConfirmRequest(TankFixtures.STATION,
                TankFixtures.nextRequestId("REFILL"), batchNo, quantity, concentration,
                "OP-A", "OP-B", TankFixtures.T.plusHours(3), expectedVersion));
    }

    @Test
    void refillCreditsAndClosesSatisfiableEventsAtomically() {
        TankFixtures.registerStandard(tankService);
        // 预占41被拦：生成待补液事件
        InjectionPlanResponse blocked = planService.plan(new InjectionPlanRequest(
                TankFixtures.STATION, TankFixtures.nextRequestId("PLAN"),
                new BigDecimal("41"), TankFixtures.T, 1));
        assertEquals("BLOCKED", blocked.status());

        RefillConfirmResponse resp = refill("BATCH-001", new BigDecimal("20"),
                new BigDecimal("20.000"), 1);

        assertEquals(0, resp.balanceKg().compareTo(new BigDecimal("70.000")));
        assertEquals(2, resp.version());
        assertEquals(List.of(blocked.refillEventId()), resp.closedEventIds(),
                "入账后可用量回到安全储备之上，关闭可满足事件");

        List<RefillEvent> events = tankService.refillEvents(TankFixtures.STATION);
        assertEquals("CLOSED", events.get(0).status());
        // 台账：REGISTER + REFILL
        List<TankLedgerEntry> ledger = tankService.ledger(TankFixtures.STATION);
        assertEquals(2, ledger.size());
        assertEquals("REFILL", ledger.get(1).entryType());
    }

    @Test
    void refillDoesNotAutoResendOldInjectionCommands() {
        TankFixtures.registerStandard(tankService);
        InjectionPlanResponse blocked = planService.plan(new InjectionPlanRequest(
                TankFixtures.STATION, TankFixtures.nextRequestId("PLAN"),
                new BigDecimal("41"), TankFixtures.T, 1));

        refill("BATCH-002", new BigDecimal("20"), new BigDecimal("20.000"), 1);

        // 旧计划保持 BLOCKED，不因入账自动重发；无新计划、无联动指令
        List<InjectionPlan> plans = tankService.plans(TankFixtures.STATION);
        assertEquals(1, plans.size());
        assertEquals("BLOCKED", plans.get(0).status());
        assertEquals(0, count("SELECT COUNT(*) FROM adjustment_issue"));
    }

    @Test
    void refillEventStaysPendingWhenStillBelowSafetyReserve() {
        // 登记余额5、安全储备10：可用量已低于安全储备
        TankFixtures.register(tankService, TankFixtures.STATION,
                TankFixtures.CAPACITY, TankFixtures.RESERVE, new BigDecimal("5"));
        planService.plan(new InjectionPlanRequest(TankFixtures.STATION,
                TankFixtures.nextRequestId("PLAN"), new BigDecimal("1"), TankFixtures.T, 1));

        // 补1kg后可用量6仍低于10：事件保持待补液
        RefillConfirmResponse small = refill("BATCH-003", new BigDecimal("1"),
                new BigDecimal("20.000"), 1);
        assertTrue(small.closedEventIds().isEmpty());
        assertEquals(1, tankService.status(TankFixtures.STATION).pendingRefillEvents());

        // 再补4kg后可用量恰等10：事件关闭
        RefillConfirmResponse enough = refill("BATCH-004", new BigDecimal("4"),
                new BigDecimal("20.000"), 2);
        assertEquals(1, enough.closedEventIds().size());
        assertEquals(0, tankService.status(TankFixtures.STATION).pendingRefillEvents());
    }

    @Test
    void singleOperatorRefillIsRejected() {
        TankFixtures.registerStandard(tankService);
        RefillConfirmRequest sameOperator = new RefillConfirmRequest(TankFixtures.STATION,
                TankFixtures.nextRequestId("REFILL"), "BATCH-010", new BigDecimal("10"),
                new BigDecimal("20.000"), "OP-A", "OP-A", TankFixtures.T, 1);
        assertThrows(BusinessRejectedException.class, () -> refillService.confirm(sameOperator));
        assertEquals(0, count("SELECT COUNT(*) FROM refill_order"), "单人交接不入账");
    }

    @Test
    void concentrationBoundaryIsClosedInterval() {
        TankFixtures.registerStandard(tankService);
        // 许可闭区间 [16.000, 24.000]：恰等边界允许
        RefillConfirmResponse lower = refill("BATCH-020", new BigDecimal("1"),
                new BigDecimal("16.000"), 1);
        assertEquals(2, lower.version());
        RefillConfirmResponse upper = refill("BATCH-021", new BigDecimal("1"),
                new BigDecimal("24.000"), 2);
        assertEquals(3, upper.version());

        // 区间外拒绝
        assertThrows(BusinessRejectedException.class,
                () -> refill("BATCH-022", new BigDecimal("1"), new BigDecimal("15.999"), 3));
        assertThrows(BusinessRejectedException.class,
                () -> refill("BATCH-023", new BigDecimal("1"), new BigDecimal("24.001"), 3));
        assertEquals(2, count("SELECT COUNT(*) FROM refill_order"));
    }

    @Test
    void refillExceedingCapacityIsRejected() {
        TankFixtures.registerStandard(tankService);
        // 余额50 + 60 > 容量100
        assertThrows(BusinessRejectedException.class,
                () -> refill("BATCH-030", new BigDecimal("60"), new BigDecimal("20.000"), 1));
        TankStatusResponse status = tankService.status(TankFixtures.STATION);
        assertEquals(0, status.balanceKg().compareTo(new BigDecimal("50.000")), "超容量不入账");
        assertEquals(1, status.version());
    }

    @Test
    void refillExactlyToCapacityIsAllowed() {
        TankFixtures.registerStandard(tankService);
        RefillConfirmResponse resp = refill("BATCH-031", new BigDecimal("50"),
                new BigDecimal("20.000"), 1);
        assertEquals(0, resp.balanceKg().compareTo(new BigDecimal("100.000")), "恰满允许");
    }

    @Test
    void duplicateBatchNoIsRejected() {
        TankFixtures.registerStandard(tankService);
        refill("BATCH-040", new BigDecimal("10"), new BigDecimal("20.000"), 1);
        // 同批号新 requestId：业务拒绝
        assertThrows(BusinessRejectedException.class,
                () -> refill("BATCH-040", new BigDecimal("10"), new BigDecimal("20.000"), 2));
        assertEquals(1, count("SELECT COUNT(*) FROM refill_order"));
        assertEquals(1, count("SELECT COUNT(*) FROM tank_ledger WHERE entry_type = 'REFILL'"),
                "重复批号不重复入账");
    }

    @Test
    void refillAfterDailyCloseDoesNotChangeSealedReconciliation() {
        // 2026-09-25 检测并日结封存
        LocalDateTime detectAt = LocalDate.of(2026, 9, 25).atTime(10, 0);
        odorantService.detect(new OdorantDetectRequest(TankFixtures.STATION,
                TankFixtures.nextRequestId("OD"), detectAt,
                new BigDecimal("100"), new BigDecimal("20.000")));
        dailyCloseService.close(new DailyCloseRequest(TankFixtures.STATION,
                TankFixtures.nextRequestId("DC"), LocalDate.of(2026, 9, 25),
                new BigDecimal("2000.000")));
        assertEquals("SEALED", jdbc.queryForObject(
                "SELECT status FROM daily_close WHERE station_id = 1", String.class));

        // 次日补液：旧勾稽不变
        TankFixtures.registerStandard(tankService);
        refill("BATCH-050", new BigDecimal("20"), new BigDecimal("20.000"), 1);

        assertEquals(1, count("SELECT COUNT(*) FROM daily_close WHERE station_id = 1 "
                + "AND status = 'SEALED' AND reported_odorant_mg = 2000.000 "
                + "AND deviation_pct = 0.0000"), "日结只冻当日台账，后续补液不改旧勾稽");
    }
}
