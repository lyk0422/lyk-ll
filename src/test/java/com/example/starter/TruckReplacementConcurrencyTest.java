package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.DispatchService;
import com.example.starter.service.FaultReportService;
import com.example.starter.service.GeneratorDispatchService;
import com.example.starter.service.GeneratorTruckService;
import com.example.starter.service.RestoreService;
import com.example.starter.service.TruckReplacementService;
import com.example.starter.support.AppClock;
import com.example.starter.web.BusinessException;
import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.ArrivalRequest;
import com.example.starter.web.dto.CreateGeneratorDispatchRequest;
import com.example.starter.web.dto.CreateReplacementRequest;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.GeneratorDispatchView;
import com.example.starter.web.dto.LoadItem;
import com.example.starter.web.dto.RegisterTruckRequest;
import com.example.starter.web.dto.ReplacementArrivalRequest;
import com.example.starter.web.dto.ReplacementView;
import com.example.starter.web.dto.RestoreConfirmRequest;
import com.example.starter.web.dto.SwitchConfirmRequest;

/**
 * 热备接替并发边界：切换与复电竞争不留孤立占用、不复电后又切入供电；
 * 同一旧车并发接替仅一单成功。真实多线程并发，CountDownLatch 同步起跑，Future 超时兜底。
 */
class TruckReplacementConcurrencyTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);
    private static final Instant D0 = T0.atZone(AppClock.ZONE).toInstant();

    @Autowired
    private FaultReportService reports;

    @Autowired
    private DispatchService dispatch;

    @Autowired
    private RestoreService restore;

    @Autowired
    private GeneratorTruckService trucks;

    @Autowired
    private GeneratorDispatchService dispatches;

    @Autowired
    private TruckReplacementService replacements;

    private int seq;

    private long newOrder() {
        seq++;
        return reports.report(new FaultReportRequest(
                "tc-r-" + seq, "F01", "接替并发故障点#" + seq, 30, "市一医院", T0)).workOrderId();
    }

    private long registerTruck(int capacityKw) {
        seq++;
        return trucks.register(new RegisterTruckRequest("tc-t-" + seq, capacityKw, "F01")).id();
    }

    private int orderVersion(long orderId) {
        return jdbc.queryForObject("SELECT version FROM work_order WHERE id = ?", Integer.class, orderId);
    }

    private GeneratorDispatchView supplyingDispatch(long order, long truckId, int loadKw) {
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "tc-d-" + (++seq), order, (long) orderVersion(order),
                List.of(new LoadItem("市一医院", loadKw)), List.of(truckId), D0));
        dispatches.arrive(created.id(), new ArrivalRequest("tc-da-" + seq, 1L, truckId,
                D0.plusSeconds(300)));
        return dispatches.view(created.id());
    }

    /** 并发执行两个任务并等待完成（10秒超时），返回各自结果或异常。 */
    private record Outcome(Object value, Throwable error) {
        boolean ok() {
            return error == null;
        }
    }

    private Outcome[] runConcurrent(ThrowingTask a, ThrowingTask b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Outcome> fa = pool.submit(() -> runOne(a, ready, start));
            Future<Outcome> fb = pool.submit(() -> runOne(b, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "线程未就绪");
            start.countDown();
            return new Outcome[] {fa.get(15, TimeUnit.SECONDS), fb.get(15, TimeUnit.SECONDS)};
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Outcome runOne(ThrowingTask task, CountDownLatch ready, CountDownLatch start) {
        try {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            return new Outcome(task.run(), null);
        } catch (Throwable e) {
            return new Outcome(null, e);
        }
    }

    @FunctionalInterface
    private interface ThrowingTask {
        Object run() throws Exception;
    }

    @Test
    void 切换与复电竞争不留孤立占用且不复电后又切入供电() throws Exception {
        freezeAt(T0);
        long order = newOrder();
        dispatch.dispatch(new DispatchRequest("tc-1-d", order, teamId("抢修一班")));
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(60);
        long n2 = registerTruck(40);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);
        ReplacementView created = replacements.create(new CreateReplacementRequest(
                "tc-1", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0));
        replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tc-1-a1", 1L, n1, D0.plusSeconds(600)));
        replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tc-1-a2", 2L, n2, D0.plusSeconds(700)));
        replacements.confirmSwitch(created.id(),
                new SwitchConfirmRequest("tc-1-c1", 3L, "操作员甲", D0.plusSeconds(800)));
        restore.confirm(new RestoreConfirmRequest("tc-1-r1", order, "DISPATCHER", "调度员甲", null));

        // 第二人切换确认 与 现场复电确认 并发竞争
        Outcome[] results = runConcurrent(
                () -> replacements.confirmSwitch(created.id(),
                        new SwitchConfirmRequest("tc-1-c2", 4L, "操作员乙", D0.plusSeconds(900))),
                () -> restore.confirm(new RestoreConfirmRequest("tc-1-r2", order, "FIELD",
                        "现场员乙", new BigDecimal("100"))));

        assertTrue(results[1].ok(), "复电确认应成功: " + results[1].error());
        String replacementStatus = jdbc.queryForObject(
                "SELECT status FROM truck_replacement WHERE id = ?", String.class, created.id());
        if (results[0].ok()) {
            assertEquals("SWITCHED", replacementStatus, "切换先完成则接替单为已切换");
        } else {
            assertTrue(results[0].error() instanceof BusinessException
                    || results[0].error() instanceof ConflictException,
                    "复电先完成则切换确认应被拒绝: " + results[0].error());
            assertEquals("CLOSED", replacementStatus, "复电先完成则接替单已关闭, 不能复电后又切入供电");
        }
        assertEquals("RESTORED", orderStatus(order));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM generator_dispatch WHERE id = ?", String.class, supplying.id()));
        assertEquals(0, count("active_occupation", "1 = 1"), "任何时序都不得留孤立占用");
        assertEquals(0, count("replacement_reservation", "1 = 1"), "任何时序都不得留孤立预占");
        assertEquals(0, count("active_replacement", "1 = 1"));
        assertEquals(1, count("supply_evidence", "dispatch_id = ?", supplying.id()), "供电证据保留");
    }

    @Test
    void 同一旧车并发接替仅一单成功() throws Exception {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(100);
        long n2 = registerTruck(120);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);

        Outcome[] results = runConcurrent(
                () -> replacements.create(new CreateReplacementRequest(
                        "tc-2a", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1), D0)),
                () -> replacements.create(new CreateReplacementRequest(
                        "tc-2b", supplying.id(), (long) supplying.version(), oldTruck, List.of(n2), D0)));

        long successes = java.util.Arrays.stream(results).filter(Outcome::ok).count();
        assertEquals(1, successes, "同一旧车并发接替仅一单成功");
        Outcome failure = java.util.Arrays.stream(results).filter(r -> !r.ok()).findFirst().orElseThrow();
        assertTrue(failure.error() instanceof BusinessException,
                "失败方应为未结束接替冲突422: " + failure.error());
        assertEquals(1, count("truck_replacement", "1 = 1"));
        assertEquals(1, count("active_replacement", "old_truck_id = ?", oldTruck));
        assertEquals(1, count("replacement_reservation", "1 = 1"), "仅成功单预占新车");
    }

    @Test
    void 并发同键同参接替创建重放同一冻结结果() throws Exception {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(100);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);

        Outcome[] results = runConcurrent(
                () -> replacements.create(new CreateReplacementRequest(
                        "tc-3", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1), D0)),
                () -> replacements.create(new CreateReplacementRequest(
                        "tc-3", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1), D0)));

        assertTrue(results[0].ok() && results[1].ok(),
                "同键同参并发应都成功: " + results[0].error() + "/" + results[1].error());
        assertEquals(results[0].value(), results[1].value(), "并发同键应重放同一冻结结果");
        assertEquals(1, count("truck_replacement", "1 = 1"), "只应落一单");
        assertEquals(1, count("replacement_reservation", "1 = 1"));
        assertEquals(1, count("gen_idempotency", "request_id = 'tc-3'"));
    }
}
