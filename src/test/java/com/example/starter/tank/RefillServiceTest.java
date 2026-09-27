package com.example.starter.tank;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.close.DailyCloseService;
import com.example.starter.web.BusinessRejectedException;
import com.example.starter.web.VersionConflictException;
import com.example.starter.web.dto.DailyCloseRequest;
import com.example.starter.web.dto.InjectionPlanRequest;
import com.example.starter.web.dto.RefillRequest;
import com.example.starter.web.dto.RefillResponse;
import com.example.starter.web.dto.TankLedgerEntryView;
import com.example.starter.web.dto.TankRegisterRequest;
import org.junit.jupiter.api.BeforeEach;
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
 * 补液双人交接：浓度许可闭区间、两名不同交接人、唯一运输批号、不超容量、
 * 原子入账关闭可满足待补液事件且不自动重发旧加注命令、日结后旧台账不变。
 */
class RefillServiceTest extends AbstractIntegrationTest {

    private static final LocalDate D = LocalDate.of(2026, 9, 25);
    private static final LocalDateTime T = D.atTime(10, 0);

    @Autowired
    private TankService tankService;
    @Autowired
    private InjectionPlanService planService;
    @Autowired
    private RefillService refillService;
    @Autowired
    private DailyCloseService dailyCloseService;

    @BeforeEach
    void registerTank() {
        // 容量100 / 安全储备10 / 初始余额50，登记后版本=1
        tankService.register(new TankRegisterRequest("ST01", "REG", new BigDecimal("100"),
                new BigDecimal("10"), new BigDecimal("50"), T));
    }

    private RefillResponse refill(String requestId, long expectedVersion, String batchNo,
                                  String quantityKg, String concentration,
                                  List<String> handlers, LocalDateTime confirmedAt) {
        return refillService.confirm(new RefillRequest("ST01", requestId, expectedVersion,
                batchNo, new BigDecimal(quantityKg), new BigDecimal(concentration),
                handlers, confirmedAt));
    }

    @Test
    void refillCreditsAndClosesSatisfiableEventsWithoutRedispatch() {
        // 需求45：可用50-45=5 < 10 → 待补液事件，计划 REJECTED
        var plan = planService.plan(new InjectionPlanRequest("ST01", "PLAN-1", 1L,
                new BigDecimal("45"), T.plusMinutes(1)));
        Long eventId = plan.refillEventId();

        RefillResponse resp = refill("REF-1", 1L, "BATCH-1", "10", "100.000",
                List.of("OP-A", "OP-B"), T.plusMinutes(2));
        assertDecimalEquals("60", resp.balanceAfterKg());
        assertEquals(List.of(eventId), resp.closedEventIds());
        assertEquals(2L, resp.version());

        // 事件已关闭；旧加注计划不因入账自动重发，仍为 REJECTED
        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM refill_event WHERE id = ?", String.class, eventId));
        assertEquals("REJECTED", jdbc.queryForObject(
                "SELECT status FROM injection_plan WHERE request_id = 'PLAN-1'", String.class));
        assertEquals(0, count("SELECT COUNT(*) FROM injection_plan WHERE status = 'PREOCCUPIED'"));
    }

    @Test
    void refillKeepsUnsatisfiableEventPending() {
        planService.plan(new InjectionPlanRequest("ST01", "PLAN-2", 1L,
                new BigDecimal("95"), T.plusMinutes(1)));
        RefillResponse resp = refill("REF-2", 1L, "BATCH-2", "10", "100.000",
                List.of("OP-A", "OP-B"), T.plusMinutes(2));
        assertTrue(resp.closedEventIds().isEmpty());
        assertEquals(1, count("SELECT COUNT(*) FROM refill_event WHERE status = 'PENDING'"));
    }

    @Test
    void refillRequiresTwoDistinctHandlers() {
        assertThrows(BusinessRejectedException.class, () -> refill("REF-3", 1L, "BATCH-3",
                "10", "100.000", List.of("OP-A", "OP-A"), T.plusMinutes(2)));
        assertEquals(0, count("SELECT COUNT(*) FROM refill_order"));
    }

    @Test
    void concentrationClosedIntervalBoundariesAccepted() {
        // 恰等边界 80.000 / 120.000 均允许
        RefillResponse low = refill("REF-4A", 1L, "BATCH-4A", "10", "80.000",
                List.of("OP-A", "OP-B"), T.plusMinutes(2));
        assertDecimalEquals("60", low.balanceAfterKg());
        RefillResponse high = refill("REF-4B", 2L, "BATCH-4B", "10", "120.000",
                List.of("OP-A", "OP-B"), T.plusMinutes(3));
        assertDecimalEquals("70", high.balanceAfterKg());
    }

    @Test
    void concentrationOutOfIntervalRejected() {
        assertThrows(BusinessRejectedException.class, () -> refill("REF-5A", 1L, "BATCH-5A",
                "10", "79.999", List.of("OP-A", "OP-B"), T.plusMinutes(2)));
        assertThrows(BusinessRejectedException.class, () -> refill("REF-5B", 1L, "BATCH-5B",
                "10", "120.001", List.of("OP-A", "OP-B"), T.plusMinutes(2)));
        assertEquals(0, count("SELECT COUNT(*) FROM refill_order"));
    }

    @Test
    void refillOverCapacityRejected() {
        // 50 + 50.001 > 100：超容量拒绝
        assertThrows(BusinessRejectedException.class, () -> refill("REF-6", 1L, "BATCH-6",
                "50.001", "100.000", List.of("OP-A", "OP-B"), T.plusMinutes(2)));
        assertDecimalEquals("50", decimal("SELECT balance_kg FROM odorant_tank WHERE station_id = 1"));
    }

    @Test
    void duplicateTransportBatchRejected() {
        refill("REF-7A", 1L, "BATCH-7", "10", "100.000", List.of("OP-A", "OP-B"), T.plusMinutes(2));
        // 唯一运输批号：不同 requestId 同批号拒绝
        assertThrows(BusinessRejectedException.class, () -> refill("REF-7B", 2L, "BATCH-7",
                "10", "100.000", List.of("OP-A", "OP-B"), T.plusMinutes(3)));
        assertEquals(1, count("SELECT COUNT(*) FROM refill_order WHERE transport_batch_no = 'BATCH-7'"));
    }

    @Test
    void refillVersionConflictReturns409() {
        assertThrows(VersionConflictException.class, () -> refill("REF-8", 9L, "BATCH-8",
                "10", "100.000", List.of("OP-A", "OP-B"), T.plusMinutes(2)));
    }

    @Test
    void failedRefillDoesNotOccupyIdempotencyKey() {
        // 业务失败不占键：同键修正参数后可正常受理（而非 409）
        assertThrows(BusinessRejectedException.class, () -> refill("REF-9", 1L, "BATCH-9",
                "10", "50.000", List.of("OP-A", "OP-B"), T.plusMinutes(2)));
        RefillResponse ok = refill("REF-9", 1L, "BATCH-9", "10", "100.000",
                List.of("OP-A", "OP-B"), T.plusMinutes(2));
        assertDecimalEquals("60", ok.balanceAfterKg());
    }

    @Test
    void refillAfterDailyCloseDoesNotTouchSealedDateLedger() {
        // 日结封存当日（无检测流量，上报0 → 偏差0% 封存）
        dailyCloseService.close(new DailyCloseRequest("ST01", "DC-1", D, BigDecimal.ZERO));
        long ledgerRowsBefore = count("SELECT COUNT(*) FROM tank_ledger WHERE station_id = 1 AND biz_date = ?",
                java.sql.Date.valueOf(D));

        // 后续补液（次日时刻）只入次日台账，不改旧勾稽
        refill("REF-10", 1L, "BATCH-10", "10", "100.000", List.of("OP-A", "OP-B"),
                D.plusDays(1).atTime(9, 0));

        assertEquals(ledgerRowsBefore,
                count("SELECT COUNT(*) FROM tank_ledger WHERE station_id = 1 AND biz_date = ?",
                        java.sql.Date.valueOf(D)), "封存日台账行数不得变化");
        List<TankLedgerEntryView> nextDay = tankService.ledger("ST01", D.plusDays(1));
        assertEquals(1, nextDay.size());
        assertEquals("REFILL", nextDay.get(0).entryType());
        assertEquals("SEALED", jdbc.queryForObject(
                "SELECT status FROM daily_close WHERE station_id = 1 AND biz_date = ?",
                String.class, java.sql.Date.valueOf(D)));
    }
}
