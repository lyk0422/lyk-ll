package com.example.starter.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.AbstractIntegrationTest;
import com.example.starter.TestInfraConfiguration;
import com.example.starter.domain.PlanStatus;
import com.example.starter.error.BusinessException;
import com.example.starter.web.dto.PlanResponse;

/**
 * 并发集成测试（真实 H2 事务）：确认锁定原子性、写操作幂等并发、执行中唯一约束。
 * 通过 CountDownLatch 协调真实并发，Future 超时获取结果，不靠休眠或打印充当验证。
 */
class PlanConcurrencyTest extends AbstractIntegrationTest {

    private static final LocalDateTime BASE = TestInfraConfiguration.BASE_TIME;
    private static final int THREADS = 8;

    @Autowired
    private PlanService planService;

    @Autowired
    private IdempotencyService idempotencyService;

    /** 并发执行若干任务并收集结果（成功响应或业务异常）。 */
    private List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch gate = new CountDownLatch(1);
        List<Callable<Object>> gated = tasks.stream().map(task -> (Callable<Object>) () -> {
            assertTrue(gate.await(10, TimeUnit.SECONDS), "并发闸门等待超时");
            return task.call();
        }).toList();
        try {
            List<Future<Object>> futures = gated.stream().map(pool::submit).toList();
            gate.countDown();
            return futures.stream().map(future -> {
                try {
                    return future.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new IllegalStateException("并发任务执行失败", e);
                }
            }).toList();
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "线程池未在超时内结束");
        }
    }

    private Callable<Object> guarding(Callable<PlanResponse> call) {
        return () -> {
            try {
                return call.call();
            } catch (BusinessException e) {
                return e;
            }
        };
    }

    private long arrangedPlan(String planNo, long pilotId, List<Long> tugs,
            LocalDateTime start, LocalDateTime end) {
        PlanResponse plan = planService.apply(planNo, "合成货轮-" + planNo, "V001");
        planService.arrange(plan.id(), pilotId, tugs, start, end);
        return plan.id();
    }

    @Test
    void 并发确认同一拖轮重叠窗口恰好一个成功() throws Exception {
        long planA = arrangedPlan("PLAN-CC-A", 1L, List.of(1L), BASE.plusHours(10), BASE.plusHours(12));
        long planB = arrangedPlan("PLAN-CC-B", 2L, List.of(1L), BASE.plusHours(11), BASE.plusHours(13));

        List<Object> results = runConcurrently(List.of(
                guarding(() -> planService.confirm(planA)),
                guarding(() -> planService.confirm(planB))));

        long successes = results.stream().filter(PlanResponse.class::isInstance).count();
        long conflicts = results.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .filter(e -> "TUG_OCCUPIED".equals(e.code())).count();
        assertEquals(1, successes, "并发确认应恰好一个成功: " + results);
        assertEquals(1, conflicts, "另一个应因拖轮占用失败: " + results);

        long lockedPlans = Math.max(lockRepository.countActiveLocksByPlan(planA),
                lockRepository.countActiveLocksByPlan(planB));
        assertEquals(2, lockedPlans, "成功计划应持有引航员+拖轮两条锁");
    }

    @Test
    void 幂等并发同键同参仅产生一条计划且返回首次结果() throws Exception {
        List<String> fields = List.of("PLAN-IDEM-1", "合成货轮", "V001");
        List<Callable<Object>> tasks = java.util.stream.IntStream.range(0, THREADS)
                .mapToObj(i -> guarding(() -> idempotencyService.execute("APPLY", "req-idem-1", fields,
                        () -> planService.apply("PLAN-IDEM-1", "合成货轮", "V001"))))
                .toList();

        List<Object> results = runConcurrently(tasks);
        long successes = results.stream().filter(PlanResponse.class::isInstance).count();
        assertEquals(THREADS, successes, "同键同参应全部拿到首次结果: " + results);
        long distinctIds = results.stream().map(PlanResponse.class::cast).map(PlanResponse::id).distinct().count();
        assertEquals(1, distinctIds, "全部并发请求应返回同一计划ID");
        assertEquals(1, planRepository.findAll().size(), "仅应创建一条计划");
        assertTrue(idempotencyRepository.findByRequestId("req-idem-1").isPresent());
    }

    @Test
    void 幂等并发同键异参恰好一个成功其余409() throws Exception {
        List<Callable<Object>> tasks = List.of(
                guarding(() -> idempotencyService.execute("APPLY", "req-idem-2",
                        List.of("PLAN-IDEM-2A", "合成货轮A", "V001"),
                        () -> planService.apply("PLAN-IDEM-2A", "合成货轮A", "V001"))),
                guarding(() -> idempotencyService.execute("APPLY", "req-idem-2",
                        List.of("PLAN-IDEM-2B", "合成货轮B", "V002"),
                        () -> planService.apply("PLAN-IDEM-2B", "合成货轮B", "V002"))));

        List<Object> results = runConcurrently(tasks);
        long successes = results.stream().filter(PlanResponse.class::isInstance).count();
        long conflicts = results.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .filter(e -> "IDEMPOTENCY_CONFLICT".equals(e.code())
                        && e.status().value() == 409)
                .count();
        assertEquals(1, successes, "同键异参并发应恰好一个成功: " + results);
        assertEquals(1, conflicts, "另一个应返回 409: " + results);
        assertEquals(1, planRepository.findAll().size(), "仅应创建一条计划");
    }

    @Test
    void 并发执行同一引航员恰好一条进入执行中() throws Exception {
        long plan1 = arrangedPlan("PLAN-EXC-1", 1L, List.of(1L), BASE.plusHours(8), BASE.plusHours(9));
        long plan2 = arrangedPlan("PLAN-EXC-2", 1L, List.of(2L), BASE.plusHours(10), BASE.plusHours(11));
        planService.confirm(plan1);
        planService.confirm(plan2);

        List<Object> results = runConcurrently(List.of(
                guarding(() -> planService.start(plan1)),
                guarding(() -> planService.start(plan2))));

        long successes = results.stream().filter(PlanResponse.class::isInstance).count();
        long conflicts = results.stream().filter(BusinessException.class::isInstance)
                .map(BusinessException.class::cast)
                .filter(e -> "PILOT_EXECUTING_CONFLICT".equals(e.code())).count();
        assertEquals(1, successes, "同一引航员并发执行应恰好一个成功: " + results);
        assertEquals(1, conflicts, "另一个应违反执行中唯一约束: " + results);
        assertEquals(1, lockRepository.countExecutingLocks(), "执行中锁仅一条");

        long executing = planRepository.findAll().stream()
                .filter(p -> p.status() == PlanStatus.EXECUTING).count();
        assertEquals(1, executing, "数据库中仅一条执行中计划");
    }
}
