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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.DispatchService;
import com.example.starter.service.FaultReportService;
import com.example.starter.service.GeneratorDispatchService;
import com.example.starter.service.GeneratorTruckService;
import com.example.starter.service.RestoreService;
import com.example.starter.support.AppClock;
import com.example.starter.web.BusinessException;
import com.example.starter.web.ConflictException;
import com.example.starter.web.dto.ArrivalRequest;
import com.example.starter.web.dto.CreateGeneratorDispatchRequest;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.GeneratorDispatchView;
import com.example.starter.web.dto.LoadItem;
import com.example.starter.web.dto.ReassignRequest;
import com.example.starter.web.dto.RegisterTruckRequest;
import com.example.starter.web.dto.RestoreConfirmRequest;

/**
 * 发电车调度并发边界：一车一占用竞争、同键并发幂等、到场逐车唯一、改派/复电与到场并发唯一。
 * 真实多线程并发，CountDownLatch 同步起跑，Future 超时兜底，断言最终结果与数据。
 */
class GeneratorConcurrencyTest extends AbstractRepairTest {

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

    private int seq;

    private long newOrder() {
        seq++;
        return reports.report(new FaultReportRequest(
                "gc-r-" + seq, "F01", "并发故障点#" + seq, 30, "市一医院", T0)).workOrderId();
    }

    private long seedTruck(int capacityKw, String feederId) {
        return jdbc.queryForObject("SELECT id FROM generator_truck WHERE capacity_kw = ? AND feeder_id = ?",
                Long.class, capacityKw, feederId);
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
    void 并发争用同一车仅一单成功() throws Exception {
        freezeAt(T0);
        long orderA = newOrder();
        long orderB = newOrder();
        long t1 = seedTruck(200, "F01");

        Outcome[] results = runConcurrent(
                () -> dispatches.create(new CreateGeneratorDispatchRequest(
                        "gc-1a", orderA, 1L, List.of(new LoadItem("市一医院", 100)), List.of(t1), D0)),
                () -> dispatches.create(new CreateGeneratorDispatchRequest(
                        "gc-1b", orderB, 1L, List.of(new LoadItem("市一医院", 100)), List.of(t1), D0)));

        long successes = java.util.Arrays.stream(results).filter(Outcome::ok).count();
        assertEquals(1, successes, "同一车并发调度仅一单成功");
        Outcome failure = java.util.Arrays.stream(results).filter(r -> !r.ok()).findFirst().orElseThrow();
        assertTrue(failure.error() instanceof BusinessException, "失败方应为占用冲突422: " + failure.error());
        assertEquals(1, count("active_occupation", "truck_id = ?", t1), "一车仅一个未结束占用");
        assertEquals(1, count("generator_dispatch", "1 = 1"));
    }

    @Test
    void 并发同键同参创建重放同一冻结结果() throws Exception {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");

        Outcome[] results = runConcurrent(
                () -> dispatches.create(new CreateGeneratorDispatchRequest(
                        "gc-2", order, 1L, List.of(new LoadItem("市一医院", 100)), List.of(t1), D0)),
                () -> dispatches.create(new CreateGeneratorDispatchRequest(
                        "gc-2", order, 1L, List.of(new LoadItem("市一医院", 100)), List.of(t1), D0)));

        assertTrue(results[0].ok() && results[1].ok(),
                "同键同参并发应都成功: " + results[0].error() + "/" + results[1].error());
        assertEquals(results[0].value(), results[1].value(), "并发同键应重放同一冻结结果");
        assertEquals(1, count("generator_dispatch", "1 = 1"), "只应落一单");
        assertEquals(1, count("active_occupation", "truck_id = ?", t1));
        assertEquals(1, count("gen_idempotency", "request_id = 'gc-2'"));
    }

    @Test
    void 并发到场同一车逐车唯一() throws Exception {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        long t2 = seedTruck(300, "F01");
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "gc-3", order, 1L, List.of(new LoadItem("市一医院", 400)), List.of(t1, t2), D0));

        Outcome[] results = runConcurrent(
                () -> dispatches.arrive(created.id(),
                        new ArrivalRequest("gc-3-a", 1L, t1, D0.plusSeconds(60))),
                () -> dispatches.arrive(created.id(),
                        new ArrivalRequest("gc-3-b", 1L, t1, D0.plusSeconds(60))));

        long successes = java.util.Arrays.stream(results).filter(Outcome::ok).count();
        assertEquals(1, successes, "同车并发到场仅一记成功");
        Outcome failure = java.util.Arrays.stream(results).filter(r -> !r.ok()).findFirst().orElseThrow();
        assertTrue(failure.error() instanceof BusinessException
                || failure.error() instanceof ConflictException,
                "失败方应为逐车唯一冲突: " + failure.error());
        assertEquals(2, jdbc.queryForObject(
                "SELECT version FROM generator_dispatch WHERE id = ?", Integer.class, created.id()),
                "到场只生效一次, 版本仅加1");
        assertEquals(1, count("dispatch_truck",
                "dispatch_id = ? AND truck_id = ? AND arrived_at IS NOT NULL", created.id(), t1));
    }

    @Test
    void 改派与到场并发唯一() throws Exception {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        long t2 = seedTruck(300, "F01");
        long t3 = trucks.register(new RegisterTruckRequest("gc-4-t", 400, "F01")).id();
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "gc-4", order, 1L, List.of(new LoadItem("市一医院", 450)), List.of(t1, t2), D0));
        dispatches.arrive(created.id(), new ArrivalRequest("gc-4-a", 1L, t1, D0.plusSeconds(60)));
        freezeAt(T0.plusMinutes(30));
        dispatches.evaluateTimeouts();
        int v = jdbc.queryForObject("SELECT version FROM generator_dispatch WHERE id = ?",
                Integer.class, created.id());

        Outcome[] results = runConcurrent(
                () -> dispatches.reassign(created.id(),
                        new ReassignRequest("gc-4-r", (long) v, List.of(t3), D0.plusSeconds(2000))),
                () -> dispatches.arrive(created.id(),
                        new ArrivalRequest("gc-4-b", (long) v, t2, D0.plusSeconds(1900))));

        // 超时后到场只是迟到事件（不变更状态与版本），改派总能成功；
        // 到场要么以LATE记录，要么因改派先行版本过期而409，两种时序都合法且效果唯一
        assertTrue(results[0].ok(), "超时改派应成功: " + results[0].error());
        if (!results[1].ok()) {
            assertTrue(results[1].error() instanceof ConflictException,
                    "到场失败仅允许版本冲突: " + results[1].error());
        } else {
            assertEquals("LATE",
                    ((com.example.starter.web.dto.ArrivalResponse) results[1].value()).outcome());
        }
        assertEquals("REASSIGNED", jdbc.queryForObject(
                "SELECT status FROM generator_dispatch WHERE id = ?", String.class, created.id()));
        assertEquals(0, count("active_occupation", "truck_id = ?", t2), "未到场车已释放且迟到不占车");
        assertEquals(0, count("dispatch_truck",
                "dispatch_id = ? AND truck_id = ? AND arrived_at IS NOT NULL", created.id(), t2),
                "迟到到场不记录到场时刻");
        long newDispatchId = jdbc.queryForObject(
                "SELECT superseded_by_id FROM generator_dispatch WHERE id = ?", Long.class, created.id());
        assertEquals(2, count("active_occupation", "dispatch_id = ?", newDispatchId),
                "新方案占用保留车与新车共两辆");
    }

    @Test
    void 复电与到场并发唯一且车辆终被释放() throws Exception {
        freezeAt(T0);
        long order = newOrder();
        dispatch.dispatch(new DispatchRequest("gc-5-d", order, teamId("抢修一班")));
        long t1 = seedTruck(200, "F01");
        int orderVersion = jdbc.queryForObject("SELECT version FROM work_order WHERE id = ?",
                Integer.class, order);
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "gc-5", order, (long) orderVersion, List.of(new LoadItem("市一医院", 150)),
                List.of(t1), D0));
        restore.confirm(new RestoreConfirmRequest("gc-5-c1", order, "DISPATCHER", "调度员甲", null));

        AtomicInteger ignored = new AtomicInteger();
        Outcome[] results = runConcurrent(
                () -> {
                    ignored.incrementAndGet();
                    return restore.confirm(new RestoreConfirmRequest("gc-5-c2", order, "FIELD",
                            "现场员乙", new BigDecimal("150")));
                },
                () -> dispatches.arrive(created.id(),
                        new ArrivalRequest("gc-5-a", 1L, t1, D0.plusSeconds(60))));

        assertTrue(results[0].ok(), "复电确认应成功: " + results[0].error());
        // 到场要么先生效后被撤销，要么因撤销后版本过期而冲突；两种时序都合法
        if (!results[1].ok()) {
            assertTrue(results[1].error() instanceof ConflictException
                    || results[1].error() instanceof BusinessException);
        }
        assertEquals("RESTORED", orderStatus(order));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM generator_dispatch WHERE id = ?", String.class, created.id()));
        assertEquals(0, count("active_occupation", "truck_id = ?", t1), "复电后车辆必须释放");
        int evidence = count("supply_evidence", "dispatch_id = ?", created.id());
        int arrived = count("dispatch_truck",
                "dispatch_id = ? AND arrived_at IS NOT NULL", created.id());
        assertTrue((evidence == 1 && arrived == 1) || (evidence == 0 && arrived == 0),
                "供电证据与到场记录必须一致: evidence=" + evidence + " arrived=" + arrived);
    }
}
