package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.common.ApiException;
import com.example.starter.elevator.Elevator;
import com.example.starter.elevator.ElevatorService;
import com.example.starter.maintenance.MaintenanceService;
import com.example.starter.rescue.RescueCase;
import com.example.starter.rescue.RescueService;

/**
 * 并发边界测试：并发接报唯一受理、强制停梯与派单竞态形成唯一终态。
 * 使用真实线程并发与数据库行锁/唯一约束，设置超时，断言最终数据。
 */
class ConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private RescueService rescueService;
    @Autowired
    private ElevatorService elevatorService;
    @Autowired
    private MaintenanceService maintenanceService;

    /**
     * 并发执行两个任务并等待二者就绪后同时放行。
     */
    private static <T> List<T> runConcurrently(java.util.concurrent.Callable<T> a,
                                               java.util.concurrent.Callable<T> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.Callable<T> wrapA = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return a.call();
            };
            java.util.concurrent.Callable<T> wrapB = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return b.call();
            };
            Future<T> fa = pool.submit(wrapA);
            Future<T> fb = pool.submit(wrapB);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 结果包装：成功值或业务异常。 */
    private record Outcome<T>(T value, ApiException error) {
        static <T> Outcome<T> of(java.util.concurrent.Callable<T> task) {
            try {
                return new Outcome<>(task.call(), null);
            } catch (ApiException e) {
                return new Outcome<>(null, e);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        boolean ok() {
            return error == null;
        }
    }

    @Test
    void concurrentReportsAcceptExactlyOne() throws Exception {
        elevatorService.create("EL-CC");

        List<Outcome<RescueCase>> results = runConcurrently(
                () -> Outcome.of(() -> rescueService.report("r-cc-1", "EL-CC", T0)),
                () -> Outcome.of(() -> rescueService.report("r-cc-2", "EL-CC", T0)));

        long succeeded = results.stream().filter(Outcome::ok).count();
        assertEquals(1, succeeded, "并发接报只能唯一受理一单");
        Outcome<RescueCase> failed = results.stream().filter(r -> !r.ok()).findFirst().orElseThrow();
        assertEquals(409, failed.error().status().value(), "另一单应返回 409");

        Integer active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM rescue_case WHERE elevator_code = 'EL-CC' AND active_key IS NOT NULL",
                Integer.class);
        assertEquals(1, active, "同一电梯同时刻只允许一个进行中救援单");
    }

    @Test
    void forceStopAndDispatchRaceYieldsUniqueTerminalState() throws Exception {
        elevatorService.create("EL-RACE");
        RescueCase c = rescueService.report("r-race", "EL-RACE", T0);

        // 线程A：维保逾期登记（第 16 日完成）→ 强制停梯锁定；线程B：对在途案件派单。
        List<Outcome<Object>> results = runConcurrently(
                () -> Outcome.of(() -> maintenanceService.register(
                        "m-race", "EL-RACE", "CERT-A001", null, null, T0.plus(16, ChronoUnit.DAYS))),
                () -> Outcome.of(() -> rescueService.dispatch(c.id(), "d-race", "调度员")));

        Outcome<Object> dispatch = results.get(1);
        Elevator elevator = elevatorService.get("EL-RACE");
        RescueCase after = rescueService.getCase(c.id());

        assertEquals(Elevator.STATUS_STOPPED, elevator.status(), "最终必须停梯");
        assertTrue(elevator.keySupervision(), "逾期应列入重点监察");

        if (dispatch.ok()) {
            assertEquals(RescueCase.DISPATCHED, after.status(),
                    "派单先于停梯提交：案件为已派单，此后不得再新派单");
        } else {
            assertEquals(409, dispatch.error().status().value(), "停梯生效后派单必须 409");
            assertEquals(RescueCase.RECEIVED, after.status(), "派单失败案件保持已接报");
        }

        long stopLogs = elevatorService.stopLog("EL-RACE").stream()
                .filter(l -> l.action().equals("STOP") && l.reason().equals("MAINTENANCE_OVERDUE")).count();
        assertEquals(1, stopLogs, "停复梯链有且仅有一条停梯记录");

        // 终态校验：无论竞态结果如何，不得出现“停梯后仍继续派单成功”。
        RescueCase terminal = rescueService.getCase(c.id());
        boolean stoppedAndNewDispatchPossible = Elevator.STATUS_STOPPED.equals(elevatorService.get("EL-RACE").status())
                && terminal.dispatchRequestId() != null && !dispatch.ok();
        assertTrue(!stoppedAndNewDispatchPossible, "不得既停梯又继续派单");
    }
}
