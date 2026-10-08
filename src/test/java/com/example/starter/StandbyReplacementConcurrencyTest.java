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
import com.example.starter.service.StandbyReplacementService;
import com.example.starter.support.AppClock;
import com.example.starter.web.BusinessException;
import com.example.starter.web.dto.ArrivalRequest;
import com.example.starter.web.dto.CreateGeneratorDispatchRequest;
import com.example.starter.web.dto.CreateReplacementRequest;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.GeneratorDispatchView;
import com.example.starter.web.dto.LoadItem;
import com.example.starter.web.dto.ReplacementArrivalRequest;
import com.example.starter.web.dto.ReplacementSwitchRequest;
import com.example.starter.web.dto.ReplacementView;
import com.example.starter.web.dto.RegisterTruckRequest;
import com.example.starter.web.dto.RestoreConfirmRequest;

/**
 * 热备接替并发边界：第二次操作员确认触发切换与正式复电竞争、末辆到场触发切换与复电竞争、
 * 同键并发申请重放、同一旧车并发申请仅一单成功。真实多线程并发，断言最终状态无孤立占用。
 */
class StandbyReplacementConcurrencyTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 28, 10, 0, 0);
    private static final Instant D0 = T0.atZone(AppClock.ZONE).toInstant();

    @Autowired
    private FaultReportService reports;

    @Autowired
    private DispatchService teamDispatch;

    @Autowired
    private RestoreService restore;

    @Autowired
    private GeneratorTruckService trucks;

    @Autowired
    private GeneratorDispatchService dispatches;

    @Autowired
    private StandbyReplacementService replacements;

    private int seq;

    private long newOrder() {
        seq++;
        return reports.report(new FaultReportRequest(
                "sc-r-" + seq, "F01", "热备并发故障点#" + seq, 30, "市一医院", T0)).workOrderId();
    }

    private long truck(String reqId, int capacityKw) {
        return trucks.register(new RegisterTruckRequest(reqId, capacityKw, "F01")).id();
    }

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

    /**
     * 构造已派工执行中的工单：100kW旧车供电中，60+40热备接替单已到齐并已有一名操作员确认，
     * 调度员复电确认也已完成；返回[工单, 调度单ID, 旧车, 接替单ID]，末步只需并发“第二确认 vs 现场复电”。
     */
    private long[] poisedForSwitchVsRestore(String prefix) {
        long order = newOrder();
        teamDispatch.dispatch(new DispatchRequest(prefix + "-d", order, teamId("抢修一班")));
        int orderV = jdbc.queryForObject("SELECT version FROM work_order WHERE id = ?",
                Integer.class, order);
        long oldTruck = truck(prefix + "-old", 100);
        long n1 = truck(prefix + "-n1", 60);
        long n2 = truck(prefix + "-n2", 40);
        GeneratorDispatchView gd = dispatches.create(new CreateGeneratorDispatchRequest(
                prefix + "-gc", order, (long) orderV, List.of(new LoadItem("市一医院", 100)),
                List.of(oldTruck), D0));
        dispatches.arrive(gd.id(),
                new ArrivalRequest(prefix + "-ga", 1L, oldTruck, D0.plusSeconds(100)));
        gd = dispatches.view(gd.id());
        ReplacementView r = replacements.create(gd.id(), new CreateReplacementRequest(
                prefix + "-rc", (long) gd.version(), oldTruck, List.of(n1, n2), D0.plusSeconds(200)));
        replacements.arrive(r.id(),
                new ReplacementArrivalRequest(prefix + "-ra1", 1L, n1, D0.plusSeconds(300)));
        replacements.arrive(r.id(),
                new ReplacementArrivalRequest(prefix + "-ra2", 2L, n2, D0.plusSeconds(310)));
        replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest(prefix + "-op1", 3L, "操作员甲", D0.plusSeconds(400)));
        restore.confirm(new RestoreConfirmRequest(prefix + "-fc1", order, "DISPATCHER", "调度员甲", null));
        return new long[] {order, gd.id(), oldTruck, r.id()};
    }

    @Test
    void 第二人确认切换与正式复电并发不留孤立占用() throws Exception {
        freezeAt(T0);
        long[] ctx = poisedForSwitchVsRestore("sc-1");
        long order = ctx[0];
        long dispatchId = ctx[1];
        long replacementId = ctx[3];

        Outcome[] results = runConcurrent(
                () -> replacements.confirmSwitch(replacementId,
                        new ReplacementSwitchRequest("sc-1-op2", 4L, "操作员乙", D0.plusSeconds(500))),
                () -> restore.confirm(new RestoreConfirmRequest("sc-1-fc2", order, "FIELD",
                        "现场员乙", new BigDecimal("100"))));

        assertTrue(results[1].ok(), "复电确认应成功: " + results[1].error());
        if (!results[0].ok()) {
            // 复电先提交：接替单已关闭，切换必须被拒绝且不得切入供电
            assertTrue(results[0].error() instanceof BusinessException,
                    "复电先行时切换只允许业务拒绝: " + results[0].error());
        }
        assertEquals("RESTORED", orderStatus(order));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM generator_dispatch WHERE id = ?", String.class, dispatchId));
        assertEquals("CANCELLED", replacements.view(replacementId).status(),
                "无论谁先谁后, 接替单最终必须关闭");
        assertEquals(0, count("active_occupation", "1 = 1"), "不得留孤立占用");
        int evidence = count("replacement_switch_evidence", "replacement_id = ?", replacementId);
        assertTrue(evidence == 0 || evidence == 1, "证据只能是切换成功一条或无");
        // 若证据存在，说明切换先于复电，此时旧车必须已释放过且最终仍无占用
        if (evidence == 1 && results[0].ok()) {
            assertEquals(0, count("active_occupation", "1 = 1"));
        }
    }

    @Test
    void 末辆到场触发切换与正式复电并发不留孤立占用() throws Exception {
        freezeAt(T0);
        long order = newOrder();
        teamDispatch.dispatch(new DispatchRequest("sc-2-d", order, teamId("抢修一班")));
        int orderV = jdbc.queryForObject("SELECT version FROM work_order WHERE id = ?",
                Integer.class, order);
        long oldTruck = truck("sc-2-old", 100);
        long n1 = truck("sc-2-n1", 60);
        long n2 = truck("sc-2-n2", 40);
        GeneratorDispatchView gd = dispatches.create(new CreateGeneratorDispatchRequest(
                "sc-2-gc", order, (long) orderV, List.of(new LoadItem("市一医院", 100)),
                List.of(oldTruck), D0));
        dispatches.arrive(gd.id(),
                new ArrivalRequest("sc-2-ga", 1L, oldTruck, D0.plusSeconds(100)));
        gd = dispatches.view(gd.id());
        ReplacementView r = replacements.create(gd.id(), new CreateReplacementRequest(
                "sc-2-rc", (long) gd.version(), oldTruck, List.of(n1, n2), D0.plusSeconds(200)));
        replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sc-2-ra1", 1L, n1, D0.plusSeconds(300)));
        // 双人先确认，只欠末辆到场
        replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest("sc-2-op1", 2L, "操作员甲", D0.plusSeconds(400)));
        replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest("sc-2-op2", 3L, "操作员乙", D0.plusSeconds(410)));
        restore.confirm(new RestoreConfirmRequest("sc-2-fc1", order, "DISPATCHER", "调度员甲", null));

        Outcome[] results = runConcurrent(
                () -> replacements.arrive(r.id(),
                        new ReplacementArrivalRequest("sc-2-ra2", 4L, n2, D0.plusSeconds(500))),
                () -> restore.confirm(new RestoreConfirmRequest("sc-2-fc2", order, "FIELD",
                        "现场员乙", new BigDecimal("100"))));

        assertTrue(results[1].ok(), "复电确认应成功: " + results[1].error());
        if (!results[0].ok()) {
            assertTrue(results[0].error() instanceof BusinessException,
                    "复电先行时末辆到场触发的切换只允许业务拒绝: " + results[0].error());
        }
        assertEquals("RESTORED", orderStatus(order));
        assertEquals("CANCELLED", replacements.view(r.id()).status());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM generator_dispatch WHERE id = ?", String.class, gd.id()));
        assertEquals(0, count("active_occupation", "1 = 1"), "不得留孤立占用");
    }

    @Test
    void 并发同键同参申请重放同一接替单() throws Exception {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = truck("sc-3-old", 100);
        long n1 = truck("sc-3-n1", 60);
        long n2 = truck("sc-3-n2", 40);
        GeneratorDispatchView gd = dispatches.create(new CreateGeneratorDispatchRequest(
                "sc-3-gc", order, 1L, List.of(new LoadItem("市一医院", 100)), List.of(oldTruck), D0));
        dispatches.arrive(gd.id(),
                new ArrivalRequest("sc-3-ga", 1L, oldTruck, D0.plusSeconds(100)));
        gd = dispatches.view(gd.id());
        final GeneratorDispatchView gdf = gd;

        Outcome[] results = runConcurrent(
                () -> replacements.create(gdf.id(), new CreateReplacementRequest(
                        "sc-3-key", (long) gdf.version(), oldTruck, List.of(n2, n1), D0.plusSeconds(200))),
                () -> replacements.create(gdf.id(), new CreateReplacementRequest(
                        "sc-3-key", (long) gdf.version(), oldTruck, List.of(n1, n2), D0.plusSeconds(200))));

        assertTrue(results[0].ok() && results[1].ok(),
                "并发同键同参应都成功: " + results[0].error() + "/" + results[1].error());
        assertEquals(results[0].value(), results[1].value(), "并发同键重放同一冻结结果");
        assertEquals(1, count("standby_replacement", "dispatch_id = ?", gdf.id()), "只落一个接替单");
        assertEquals(1, count("gen_idempotency", "request_id = 'sc-3-key'"));
        assertEquals(1, count("active_occupation", "truck_id = ?", n1), "新车仅一条热备预占");
        assertEquals(1, count("active_occupation", "truck_id = ?", n2));
    }

    @Test
    void 同一旧车并发申请接替仅一单成功() throws Exception {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = truck("sc-4-old", 100);
        long n1 = truck("sc-4-n1", 100);
        long n2 = truck("sc-4-n2", 100);
        GeneratorDispatchView gd = dispatches.create(new CreateGeneratorDispatchRequest(
                "sc-4-gc", order, 1L, List.of(new LoadItem("市一医院", 100)), List.of(oldTruck), D0));
        dispatches.arrive(gd.id(),
                new ArrivalRequest("sc-4-ga", 1L, oldTruck, D0.plusSeconds(100)));
        gd = dispatches.view(gd.id());
        final GeneratorDispatchView gdf = gd;

        Outcome[] results = runConcurrent(
                () -> replacements.create(gdf.id(), new CreateReplacementRequest(
                        "sc-4-a", (long) gdf.version(), oldTruck, List.of(n1), D0.plusSeconds(200))),
                () -> replacements.create(gdf.id(), new CreateReplacementRequest(
                        "sc-4-b", (long) gdf.version(), oldTruck, List.of(n2), D0.plusSeconds(200))));

        long successes = java.util.Arrays.stream(results).filter(Outcome::ok).count();
        assertEquals(1, successes, "同一旧车并发接替仅一单成功");
        Outcome failure = java.util.Arrays.stream(results).filter(r -> !r.ok()).findFirst().orElseThrow();
        assertTrue(failure.error() instanceof BusinessException,
                "失败方应为每旧车唯一接替的业务拒绝: " + failure.error());
        assertEquals(1, count("standby_replacement", "old_truck_id = ? AND status = 'WAITING'", oldTruck));
        assertEquals(1, count("active_occupation", "occupation_type = 'STANDBY'"),
                "仅成功单的新车被预占");
    }
}
