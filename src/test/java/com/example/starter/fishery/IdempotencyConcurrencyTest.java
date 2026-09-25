package com.example.starter.fishery;

import com.example.starter.fishery.api.ConflictException;
import com.example.starter.fishery.api.dto.DepartureRequest;
import com.example.starter.fishery.api.dto.DepartureResponse;
import com.example.starter.fishery.api.dto.LogbookResponse;
import com.example.starter.fishery.api.dto.SettlementRequest;
import com.example.starter.fishery.api.dto.SettlementResponse;
import com.example.starter.fishery.domain.TripStatus;
import com.example.starter.fishery.init.SyntheticDataInitializer;
import com.example.starter.fishery.service.FisheryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幂等重放/异参冲突、结案终态与迟到日志、同一渔船唯一在航，以及真实并发核销不超配额。
 */
@SpringBootTest
class IdempotencyConcurrencyTest extends AbstractFisheryTest {

    @Autowired
    private FisheryService service;

    private String vessel;

    @BeforeEach
    void setUp() {
        vessel = registerVesselWithQuota(SyntheticDataInitializer.SPECIES_HAIRTAIL, "100.000");
    }

    @AfterEach
    void tearDown() {
        cleanup();
    }

    @Test
    void sameRequestSameParams_replaysFirstResultWithoutDuplicateData() {
        String trip = "T-IDEM-REPLAY";
        String requestId = newRequestId();
        DepartureRequest request = new DepartureRequest(requestId, trip, vessel, T0,
                List.of(new DepartureRequest.SpeciesWeight(
                        SyntheticDataInitializer.SPECIES_HAIRTAIL, new BigDecimal("50.000"))));

        DepartureResponse first = service.declareDeparture(request);
        assertFalse(first.replayed());

        // 同键同参重放返回首次结果，不重复建航次
        DepartureResponse replay = service.declareDeparture(request);
        assertTrue(replay.replayed(), "同键同参必须标记为重放");
        assertEquals(first.tripCode(), replay.tripCode());
        Integer tripCount = jdbc.queryForObject(
                "SELECT COUNT(1) FROM trip WHERE trip_code = ?", Integer.class, trip);
        assertEquals(1, tripCount);
    }

    @Test
    void sameRequestDifferentParams_returnsConflict() {
        String trip = "T-IDEM-CONFLICT";
        String requestId = newRequestId();
        DepartureRequest first = new DepartureRequest(requestId, trip, vessel, T0,
                List.of(new DepartureRequest.SpeciesWeight(
                        SyntheticDataInitializer.SPECIES_HAIRTAIL, new BigDecimal("50.000"))));
        service.declareDeparture(first);

        // 同 requestId 但业务参数（重量）不同 → 409
        DepartureRequest mismatched = new DepartureRequest(requestId, trip, vessel, T0,
                List.of(new DepartureRequest.SpeciesWeight(
                        SyntheticDataInitializer.SPECIES_HAIRTAIL, new BigDecimal("51.000"))));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.declareDeparture(mismatched));
        assertTrue(ex.getMessage().contains("不同业务参数"));
    }

    @Test
    void oneActiveTripPerVessel_secondDepartureWhileOpenRejected() {
        service.declareDeparture(departureRequest("T-ACTIVE-1", vessel, T0,
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "10.000"));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.declareDeparture(departureRequest("T-ACTIVE-2", vessel,
                        T0.plusSeconds(100), SyntheticDataInitializer.SPECIES_HAIRTAIL, "10.000")));
        assertTrue(ex.getMessage().contains("唯一受理"));
    }

    @Test
    void closedTripIsTerminal_repeatedSettlementIsIdempotentAndNoDuplicateDeduction() {
        String trip = "T-CLOSE-IDEM";
        runTripToDischarged(service, vessel, trip,
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "50.000");
        service.settle(settlementRequest(trip));

        // 结案后重复结案：幂等成功，不重复扣减/不重复明细
        SettlementResponse repeat = service.settle(new SettlementRequest(newRequestId(), trip));
        assertEquals(TripStatus.CLOSED, repeat.status());
        assertTrue(repeat.replayed());
        assertEquals(0, usedQuota(vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL)
                .compareTo(new BigDecimal("50.000")));
        assertEquals(1L, countSettlements(trip));
    }

    @Test
    void lateLogAfterClosed_isOnlyRecordedAsLateEventAndReviewQueued() {
        String trip = "T-LATE";
        runTripToDischarged(service, vessel, trip,
                SyntheticDataInitializer.SPECIES_HAIRTAIL, "50.000");
        service.settle(settlementRequest(trip));

        // 结案后迟到日志：标记 late、入审查队列，但不参与核销
        LogbookResponse late = service.submitLogbook(logbookRequest(trip, "Z-LATE",
                T0.plusSeconds(100_000),
                List.of(catchLine(SyntheticDataInitializer.SPECIES_HAIRTAIL, "999.000"))));
        assertTrue(late.late());
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM late_log_event WHERE trip_code = ?", Integer.class, trip));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(1) FROM review_case WHERE trip_code = ? AND reason = 'LATE_LOG'",
                Integer.class, trip));

        // 已核销扣减量不受迟到日志影响，航次仍结案
        assertEquals(0, usedQuota(vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL)
                .compareTo(new BigDecimal("50.000")));
        assertEquals("CLOSED", tripStatus(trip));

        // 对迟到日志案件下结论：只关闭审查队列，不重新核销、不新增明细
        Long lateReviewId = jdbc.queryForObject(
                "SELECT id FROM review_case WHERE trip_code = ? AND reason = 'LATE_LOG'",
                Long.class, trip);
        com.example.starter.fishery.api.dto.ReviewDecisionResponse decision = service.decideReview(
                lateReviewId, new com.example.starter.fishery.api.dto.ReviewDecisionRequest(
                        newRequestId(),
                        com.example.starter.fishery.domain.ReviewConclusion.CONFIRM_LOGGED,
                        "迟到日志仅备查，不改原核销", null));
        assertFalse(decision.settled());
        assertEquals(TripStatus.CLOSED, decision.tripStatus());
        assertEquals(1L, countSettlements(trip));
        assertEquals(0, usedQuota(vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL)
                .compareTo(new BigDecimal("50.000")));
    }

    /**
     * 真实并发核销：直接在库中构造同船两个已进港航次（白盒绕过“唯一在航”出港护栏），
     * 各最终口径 60kg、配额 100kg；两线程同时核销，行锁必须保证恰好一个成功、合计不超配额。
     */
    @Test
    void concurrentSettlementsOfTwoTrips_mustNeverExceedQuota() throws Exception {
        insertDischargedTripWithCatch("T-CONC-1", 60.0);
        insertDischargedTripWithCatch("T-CONC-2", 60.0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        java.util.Map<String, String> requestIdByTrip = new java.util.HashMap<>();
        for (String tripCode : List.of("T-CONC-1", "T-CONC-2")) {
            requestIdByTrip.put(tripCode, newRequestId());
        }
        for (String tripCode : List.of("T-CONC-1", "T-CONC-2")) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await(10, TimeUnit.SECONDS);
                    service.settle(new SettlementRequest(requestIdByTrip.get(tripCode), tripCode));
                    success.incrementAndGet();
                } catch (com.example.starter.fishery.api.BusinessRuleException ex) {
                    rejected.incrementAndGet();
                } catch (Throwable ex) {
                    unexpected.compareAndSet(null, ex);
                }
            });
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发核销必须在超时前完成");

        org.junit.jupiter.api.Assertions.assertNull(unexpected.get());
        assertEquals(1, success.get(), "恰好一个航次核销成功");
        assertEquals(1, rejected.get(), "另一个必须因超配额整笔拒绝");
        assertEquals(0, usedQuota(vessel, SyntheticDataInitializer.SPECIES_HAIRTAIL)
                .compareTo(new BigDecimal("60.000")), "合计扣减不得超过配额（used=60 而非 120）");

        long closed = jdbc.queryForObject(
                "SELECT COUNT(1) FROM trip WHERE status = 'CLOSED'", Long.class);
        long discharged = jdbc.queryForObject(
                "SELECT COUNT(1) FROM trip WHERE status = 'DISCHARGED'", Long.class);
        assertEquals(1L, closed);
        assertEquals(1L, discharged, "被拒绝航次必须保持未结案，不出现余额已扣而未结案");
    }

    /** 直接构造一个已进港、final_kg 已确定但未核销的航次（仅并发白盒测试使用）。 */
    private void insertDischargedTripWithCatch(String tripCode, double finalKg) {
        Instant now = Instant.parse("2026-03-14T12:00:00Z");
        jdbc.update("INSERT INTO trip(trip_code, vessel_code, depart_at, landed_at, status, frozen, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'DISCHARGED', FALSE, ?, ?)",
                tripCode, vessel, Timestamp.from(T0), Timestamp.from(T0.plusSeconds(7200)),
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("INSERT INTO landing(trip_code, landed_at, total_kg, deviation_pct, review_triggered, "
                        + "created_at) VALUES (?, ?, ?, 0, FALSE, ?)",
                tripCode, Timestamp.from(T0.plusSeconds(7200)),
                BigDecimal.valueOf(finalKg), Timestamp.from(now));
        jdbc.update("INSERT INTO trip_catch_line(trip_code, species_code, logged_kg, landed_kg, final_kg) "
                        + "VALUES (?, ?, ?, ?, ?)",
                tripCode, SyntheticDataInitializer.SPECIES_HAIRTAIL,
                BigDecimal.valueOf(finalKg), BigDecimal.valueOf(finalKg), BigDecimal.valueOf(finalKg));
    }
}
