package com.example.starter.wb.service;

import com.example.starter.wb.AbstractIntegrationTest;
import com.example.starter.wb.dto.AssemblyItemRequest;
import com.example.starter.wb.dto.AssemblyPlanResponse;
import com.example.starter.wb.dto.AssemblySubmitRequest;
import com.example.starter.wb.dto.CheckinRequest;
import com.example.starter.wb.dto.CreateManifestRequest;
import com.example.starter.wb.dto.LoadItem;
import com.example.starter.wb.dto.StageRequest;
import com.example.starter.wb.exception.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 集装器装配并发测试：同航班并发改图唯一成功、同键并发回放一致、
 * 两航班抢同一集装器最多一方成功。
 */
class AssemblyConcurrencyTest extends AbstractIntegrationTest {

    private static final BigDecimal FUEL = new BigDecimal("5000");
    private static final LocalDateTime SUBMITTED_AT = LocalDateTime.of(2026, 9, 26, 8, 30, 0);

    @Autowired
    private WeightBalanceService wbService;

    @Autowired
    private AssemblyService assemblyService;

    private static String rid() {
        return UUID.randomUUID().toString();
    }

    private static List<LoadItem> items() {
        return List.of(
                new LoadItem("PASSENGER", "PAX_F", 90, new BigDecimal("80")),
                new LoadItem("CARGO", "CARGO_F", 6, new BigDecimal("1000")));
    }

    private static List<AssemblyItemRequest> planItems(String first, String second) {
        return List.of(
                new AssemblyItemRequest(first, "AKE", "ULD_P1", 80, 2920),
                new AssemblyItemRequest(second, "AKE", "ULD_P2", 80, 2920));
    }

    private long controlledLoaded(long flightId) {
        wbService.createManifest(new CreateManifestRequest(rid(), flightId, FUEL, "lc001", items(), true));
        wbService.checkin(flightId, new CheckinRequest(rid(), items(), FUEL, "agent01"));
        return wbService.confirmLoad(flightId, new StageRequest(rid(), "loader01")).manifestId();
    }

    @Test
    void concurrentSubmitsSameFlightExactlyOneSucceeds() throws Exception {
        controlledLoaded(1L);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    assemblyService.submit(1L, new AssemblySubmitRequest(rid(), 1, 0,
                            planItems("ULD-A" + n, "ULD-B" + n), "uld01", SUBMITTED_AT));
                    success.incrementAndGet();
                } catch (ConflictException e) {
                    conflict.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(threads - 1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assembly_plan", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM uld_occupation", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentSameRequestIdSameParamsAllGetFirstResult() throws Exception {
        controlledLoaded(1L);
        String requestId = rid();
        List<AssemblyItemRequest> items = planItems("ULD001", "ULD002");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<AssemblyPlanResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return assemblyService.submit(1L,
                        new AssemblySubmitRequest(requestId, 1, 0, items, "uld01", SUBMITTED_AT));
            }));
        }
        start.countDown();
        List<AssemblyPlanResponse> responses = new ArrayList<>();
        for (Future<AssemblyPlanResponse> f : futures) {
            responses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        // 全部线程拿到首次冻结结果，装配图只有一版
        Long planId = responses.get(0).planId();
        assertThat(responses).allSatisfy(r -> assertThat(r.planId()).isEqualTo(planId));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assembly_plan", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotent_request WHERE request_id=?",
                Integer.class, requestId)).isEqualTo(1);
    }

    @Test
    void twoFlightsGrabbingSameUldExactlyOneSucceeds() throws Exception {
        // 第二个合成航班（同机型）
        jdbc.update("MERGE INTO flight (id, flight_no, origin, destination, aircraft_id, scheduled_time, created_at) "
                        + "KEY (id) VALUES (2, 'CA9999', 'PEK', 'CAN', 1, "
                        + "TIMESTAMP '2026-09-27 10:00:00', TIMESTAMP '2026-09-27 06:00:00')");
        controlledLoaded(1L);
        controlledLoaded(2L);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Runnable grab1 = () -> {
            awaitQuiet(start);
            try {
                assemblyService.submit(1L, new AssemblySubmitRequest(rid(), 1, 0,
                        planItems("ULD-SHARED", "ULD-A1"), "uld01", SUBMITTED_AT));
                success.incrementAndGet();
            } catch (ConflictException e) {
                conflict.incrementAndGet();
            }
        };
        Runnable grab2 = () -> {
            awaitQuiet(start);
            try {
                assemblyService.submit(2L, new AssemblySubmitRequest(rid(), 1, 0,
                        planItems("ULD-SHARED", "ULD-B1"), "uld02", SUBMITTED_AT));
                success.incrementAndGet();
            } catch (ConflictException e) {
                conflict.incrementAndGet();
            }
        };
        Future<?> f1 = pool.submit(grab1);
        Future<?> f2 = pool.submit(grab2);
        start.countDown();
        f1.get(30, TimeUnit.SECONDS);
        f2.get(30, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        // 同一编号全库最多一笔占用
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM uld_occupation WHERE uld_number='ULD-SHARED'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM assembly_plan", Integer.class)).isEqualTo(1);
    }

    private static void awaitQuiet(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
