package com.example.starter.tailings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.example.starter.tailings.error.BusinessException;
import com.example.starter.tailings.service.FloodRoutingService;
import com.example.starter.tailings.service.PlanService;
import com.example.starter.tailings.support.IntegrationTestSupport;

/**
 * 泄流方案：泄量恰等安全值合法、超限拒绝、判定门槛、并发下唯一生效方案。
 */
class PlanServiceTest extends IntegrationTestSupport {

    @Test
    void dischargeExactlyEqualsSafeValueIsLegal() {
        long pondId = createPond();
        FloodRoutingService.CalcResult calc = calc(pondId, "900000", "60", "3");
        PlanService.PlanResult plan = planService.create(new PlanService.PlanRequest(
                rid(), calc.calcId(), List.of(new PlanService.OrificeInput("1#孔", bd("60")),
                        new PlanService.OrificeInput("2#孔", bd("40")))));
        assertEquals(0, bd("100").compareTo(plan.totalDischarge()));
        assertEquals("EFFECTIVE", plan.status());
    }

    @Test
    void dischargeExceedingSafeValueRejectedAndRolledBack() {
        long pondId = createPond();
        FloodRoutingService.CalcResult calc = calc(pondId, "900000", "60", "3");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> planService.create(new PlanService.PlanRequest(
                        rid(), calc.calcId(), List.of(new PlanService.OrificeInput("1#孔", bd("100.001"))))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM discharge_plan", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pond_active_plan", Integer.class));
    }

    @Test
    void planRejectedWhenVerdictDoesNotRequireDischarge() {
        long pondId = createPond();
        FloodRoutingService.CalcResult calc = calc(pondId, "100", "60", "3");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> planService.create(new PlanService.PlanRequest(
                        rid(), calc.calcId(), List.of(new PlanService.OrificeInput("1#孔", bd("10"))))));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
    }

    @Test
    void secondEffectivePlanRejected() {
        long pondId = createPond();
        FloodRoutingService.CalcResult calc = calc(pondId, "900000", "60", "3");
        planService.create(new PlanService.PlanRequest(
                rid(), calc.calcId(), List.of(new PlanService.OrificeInput("1#孔", bd("80")))));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> planService.create(new PlanService.PlanRequest(
                        rid(), calc.calcId(), List.of(new PlanService.OrificeInput("2#孔", bd("70"))))));
        assertEquals(HttpStatus.CONFLICT, ex.status());
    }

    @Test
    void concurrentPlansYieldSingleEffectivePlan() throws Exception {
        long pondId = createPond();
        FloodRoutingService.CalcResult calc = calc(pondId, "900000", "60", "3");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = java.util.stream.IntStream.range(0, threads)
                .<Future<?>>mapToObj(i -> pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        planService.create(new PlanService.PlanRequest(rid(), calc.calcId(),
                                List.of(new PlanService.OrificeInput("孔" + i, bd("50")))));
                        success.incrementAndGet();
                    } catch (BusinessException e) {
                        assertEquals(HttpStatus.CONFLICT, e.status());
                        conflict.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }))
                .toList();
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1, success.get());
        assertEquals(threads - 1, conflict.get());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM discharge_plan", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM pond_active_plan", Integer.class));
    }
}
