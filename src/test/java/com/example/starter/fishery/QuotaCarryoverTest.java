package com.example.starter.fishery;

import com.example.starter.fishery.api.BusinessRuleException;
import com.example.starter.fishery.api.ConflictException;
import com.example.starter.fishery.api.dto.CarryoverRequest;
import com.example.starter.fishery.api.dto.CarryoverResponse;
import com.example.starter.fishery.api.dto.ReconcileResponse;
import com.example.starter.fishery.init.SyntheticDataInitializer;
import com.example.starter.fishery.service.FisheryService;
import com.example.starter.fishery.service.QuotaAdjustmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 年度结转：20% 上限（恰 20% 合法）、产地预警拒绝、次年额度初始化原子完成、
 * 结转额度次年台账单列并优先核销、幂等与并发唯一受理。
 */
@SpringBootTest
class QuotaCarryoverTest extends AbstractFisheryTest {

    private static final String SPECIES = SyntheticDataInitializer.SPECIES_YELLOW_CROAKER;

    /** 结转来源年度：当前 UTC 年度的上一年（服务按注入时钟判定年度末）。 */
    private static final int FROM_YEAR = Instant.now().atZone(ZoneOffset.UTC).getYear() - 1;
    private static final int TO_YEAR = FROM_YEAR + 1;

    @Autowired
    private QuotaAdjustmentService adjustmentService;

    @Autowired
    private FisheryService fisheryService;

    private String vessel;

    @BeforeEach
    void setUp() {
        vessel = registerVessel("VC-" + System.nanoTime(), java.util.Map.of());
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    private CarryoverRequest carryoverRequest() {
        return new CarryoverRequest(newRequestId(), vessel, SPECIES, FROM_YEAR);
    }

    private void seedSourceQuota(String quotaKg, String usedKg) {
        addQuota(vessel, SPECIES, FROM_YEAR, quotaKg);
        setUsedQuota(vessel, SPECIES, FROM_YEAR, usedKg);
    }

    private BigDecimal carryoverUsed() {
        BigDecimal used = jdbc.queryForObject(
                "SELECT used_kg FROM quota_carryover WHERE vessel_code = ? AND species_code = ? AND from_year = ?",
                BigDecimal.class, vessel, SPECIES, FROM_YEAR);
        return used == null ? null : used.stripTrailingZeros();
    }

    @Test
    void carryover_exactly20Percent_isLegal() {
        // 未用 200 = 年度配额 1000 的 20%，恰 20% 合法
        seedSourceQuota("1000.000", "800.000");

        CarryoverResponse response = adjustmentService.carryover(carryoverRequest());

        assertEquals(FROM_YEAR, response.fromYear());
        assertEquals(TO_YEAR, response.toYear());
        assertEquals(0, response.amountKg().compareTo(new BigDecimal("200.000")));
        assertFalse(response.replayed());
        // 结转与次年额度初始化原子完成：次年台账已建行且结转额度单列
        assertEquals(0, quotaKg(vessel, SPECIES, TO_YEAR).compareTo(new BigDecimal("0.000")));
        BigDecimal carried = jdbc.queryForObject(
                "SELECT amount_kg FROM quota_carryover WHERE vessel_code = ? AND species_code = ? AND from_year = ?",
                BigDecimal.class, vessel, SPECIES, FROM_YEAR);
        assertEquals(0, carried.stripTrailingZeros().compareTo(new BigDecimal("200.000")));
    }

    @Test
    void carryover_capsAt20PercentWhenRemainingLarger() {
        // 未用 1000，超过 20% 上限，按 200 结转
        seedSourceQuota("1000.000", "0.000");

        CarryoverResponse response = adjustmentService.carryover(carryoverRequest());
        assertEquals(0, response.amountKg().compareTo(new BigDecimal("200.000")));
    }

    @Test
    void carryover_remainingBelowCap_carriesRemaining() {
        // 未用 50 低于 20% 上限，按实际未用结转
        seedSourceQuota("1000.000", "950.000");

        CarryoverResponse response = adjustmentService.carryover(carryoverRequest());
        assertEquals(0, response.amountKg().compareTo(new BigDecimal("50.000")));
    }

    @Test
    void carryover_alertTriggered_rejected() {
        seedSourceQuota("1000.000", "100.000");
        setQuotaAlert(vessel, SPECIES, FROM_YEAR, true);

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> adjustmentService.carryover(carryoverRequest()));
        assertTrue(ex.getMessage().contains("产地预警"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota_carryover WHERE vessel_code = ?", Integer.class, vessel));
    }

    @Test
    void carryover_yearNotEnded_rejected() {
        int currentYear = Instant.now().atZone(ZoneOffset.UTC).getYear();
        addQuota(vessel, SPECIES, currentYear, "100.000");

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> adjustmentService.carryover(
                        new CarryoverRequest(newRequestId(), vessel, SPECIES, currentYear)));
        assertTrue(ex.getMessage().contains("尚未结束"));
    }

    @Test
    void carryover_noRemaining_rejected() {
        seedSourceQuota("1000.000", "1000.000");

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> adjustmentService.carryover(carryoverRequest()));
        assertTrue(ex.getMessage().contains("无未用配额"));
    }

    @Test
    void carryover_duplicate_conflictAndIdempotentReplay() {
        seedSourceQuota("1000.000", "900.000");
        CarryoverRequest request = carryoverRequest();
        CarryoverResponse first = adjustmentService.carryover(request);

        // 同键同参重放：返回首次结果，不重复结转
        CarryoverResponse replay = adjustmentService.carryover(request);
        assertTrue(replay.replayed());
        assertEquals(first.amountKg(), replay.amountKg());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota_carryover WHERE vessel_code = ?", Integer.class, vessel));

        // 同键异参（鱼种不同）→ 409
        assertThrows(ConflictException.class,
                () -> adjustmentService.carryover(new CarryoverRequest(
                        request.requestId(), vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL, FROM_YEAR)));

        // 不同 requestId 重复结转同一额度 → 409（唯一约束）
        ConflictException ex = assertThrows(ConflictException.class,
                () -> adjustmentService.carryover(carryoverRequest()));
        assertTrue(ex.getMessage().contains("已结转"));
    }

    @Test
    void settlement_consumesCarryoverFirstThenAnnualQuota() {
        // 上年配额 1000 已用 900 → 结转 100 至次年；次年核定 200
        seedSourceQuota("1000.000", "900.000");
        addQuota(vessel, SPECIES, TO_YEAR, "200.000");
        adjustmentService.carryover(carryoverRequest());

        // 次年度航次核销 150：先扣结转 100，再扣年度配额 50
        Instant departAt = LocalDate.of(TO_YEAR, 3, 14).atStartOfDay().toInstant(ZoneOffset.UTC);
        String trip = "T-CARRY-" + System.nanoTime();
        runTripToDischarged(fisheryService, vessel, trip, SPECIES, "150.000",
                "Z-CARRY", departAt, departAt.plusSeconds(3600), departAt.plusSeconds(7200));
        fisheryService.settle(settlementRequest(trip));

        assertEquals(0, carryoverUsed().compareTo(new BigDecimal("100.000")),
                "结转额度次年优先核销");
        assertEquals(0, usedQuota(vessel, SPECIES, TO_YEAR).compareTo(new BigDecimal("50.000")),
                "超出结转部分再扣年度配额");
        // 台账复算一致（used_kg + 结转已核销 = 核销明细合计）
        ReconcileResponse reconcile = fisheryService.reconcile(trip);
        assertTrue(reconcile.ledgerConsistent());
    }

    @Test
    void settlement_withinCarryover_annualQuotaUntouched() {
        seedSourceQuota("1000.000", "900.000");
        adjustmentService.carryover(carryoverRequest());

        Instant departAt = LocalDate.of(TO_YEAR, 4, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        String trip = "T-CARRY-ONLY-" + System.nanoTime();
        runTripToDischarged(fisheryService, vessel, trip, SPECIES, "60.000",
                "Z-CARRY-ONLY", departAt, departAt.plusSeconds(3600), departAt.plusSeconds(7200));
        fisheryService.settle(settlementRequest(trip));

        assertEquals(0, carryoverUsed().compareTo(new BigDecimal("60.000")));
        assertEquals(0, usedQuota(vessel, SPECIES, TO_YEAR).compareTo(new BigDecimal("0.000")),
                "结转额度足够时年度配额不扣减");
    }

    @Test
    void carryover_concurrentSameQuota_exactlyOneAccepted() throws Exception {
        seedSourceQuota("1000.000", "900.000");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    adjustmentService.carryover(carryoverRequest());
                    success.incrementAndGet();
                } catch (ConflictException ex) {
                    conflict.incrementAndGet();
                } catch (Throwable ex) {
                    unexpected.compareAndSet(null, ex);
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发结转必须在超时前完成");

        assertNull(unexpected.get());
        assertEquals(1, success.get(), "同一额度只结转一次");
        assertEquals(1, conflict.get());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota_carryover WHERE vessel_code = ?", Integer.class, vessel));
        // 次年额度只初始化一次
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM quota WHERE vessel_code = ? AND species_code = ? AND quota_year = ?",
                Integer.class, vessel, SPECIES, TO_YEAR));
    }
}
