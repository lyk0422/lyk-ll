package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import com.example.starter.service.RestoreService;
import com.example.starter.support.Fingerprints;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.GeneratorDispatchRequest;
import com.example.starter.web.dto.GeneratorDispatchResponse;
import com.example.starter.web.dto.LoadItemRequest;
import com.example.starter.web.dto.RestoreConfirmRequest;
import com.example.starter.web.dto.VehicleArrivalRequest;
import com.example.starter.web.dto.VehicleArrivalResponse;
import com.example.starter.web.dto.VehicleRegisterRequest;

/**
 * 真实多线程 H2 并发测试：组合调度车冲突、改派唯一、到场与正式复电竞争，
 * 以及结构化指纹对含分隔符文本的无歧义区分。
 */
class GeneratorConcurrencyTest extends AbstractRepairTest {

    private static final LocalDateTime U0 = LocalDateTime.of(2026, 9, 26, 2, 0, 0);
    private static final LocalDateTime S0 = U0.plusHours(8);

    @Autowired
    private GeneratorDispatchService generator;
    @Autowired
    private FaultReportService reports;
    @Autowired
    private DispatchService teamDispatch;
    @Autowired
    private RestoreService restore;

    private long order(String requestId) {
        return reports.report(new FaultReportRequest(
                requestId, "F01", "并发点-" + requestId, 30, "市一医院", S0)).workOrderId();
    }

    private long vehicle(String reqId, String code, int kw) {
        return generator.registerVehicle(
                new VehicleRegisterRequest(reqId, code, kw, "F01", true)).id();
    }

    @Test
    void 并发争抢同一发电车恰好一单成功且无双占() throws Exception {
        freezeAt(S0);
        long o1 = order("gc-1");
        long o2 = order("gc-2");
        long shared = vehicle("gc-v1", "共用500", 500);
        long a = vehicle("gc-v2", "甲300", 300);
        long b = vehicle("gc-v3", "乙300", 300);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<GeneratorDispatchResponse> f1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return generator.dispatch(new GeneratorDispatchRequest("gc-d1", o1, 1,
                        List.of(shared, a), List.of(new LoadItemRequest("市一医院", 800)), U0));
            });
            Future<GeneratorDispatchResponse> f2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return generator.dispatch(new GeneratorDispatchRequest("gc-d2", o2, 1,
                        List.of(shared, b), List.of(new LoadItemRequest("市一医院", 800)), U0));
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            int success = 0;
            int rejected = 0;
            for (Future<GeneratorDispatchResponse> f : List.of(f1, f2)) {
                try {
                    f.get(15, TimeUnit.SECONDS);
                    success++;
                } catch (Exception e) {
                    if (e.getCause() instanceof UnprocessableException) {
                        rejected++;
                    } else {
                        throw new AssertionError("非预期异常", e.getCause());
                    }
                }
            }
            assertEquals(1, success, "恰好一单成功");
            assertEquals(1, rejected, "冲突单整单不派");
            // 共用车只有一条未释放占用，且不存在任何重复占用
            assertEquals(1, count("generator_occupation",
                    "vehicle_id = ? AND released_at IS NULL", shared));
            // 被拒整单不落库
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM generator_dispatch_order", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 并发改派同一超时单恰好一张新单() throws Exception {
        freezeAt(S0);
        long o = order("gc-3");
        long g1 = vehicle("gc-v4", "改A500", 500);
        long g2 = vehicle("gc-v5", "改B300", 300);
        long n1 = vehicle("gc-v6", "新甲400", 400);
        long n2 = vehicle("gc-v7", "新乙400", 400);
        GeneratorDispatchResponse d = generator.dispatch(new GeneratorDispatchRequest(
                "gc-d3", o, 1, List.of(g1, g2),
                List.of(new LoadItemRequest("市一医院", 800)), U0));
        generator.arrive(new VehicleArrivalRequest("gc-a1", d.orderId(), 1, g1, U0.plusMinutes(5)));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return generator.reassign(new com.example.starter.web.dto.GeneratorReassignRequest(
                        "gc-r1", d.orderId(), 3, List.of(n1), U0.plusMinutes(30)));
            });
            Future<?> f2 = pool.submit(() -> {
                ready.countDown();
                start.await();
                return generator.reassign(new com.example.starter.web.dto.GeneratorReassignRequest(
                        "gc-r2", d.orderId(), 3, List.of(n2), U0.plusMinutes(30)));
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            AtomicInteger success = new AtomicInteger();
            final long[] winnerVehicle = new long[1];
            for (Future<?> f : List.of(f1, f2)) {
                try {
                    com.example.starter.web.dto.GeneratorReassignResponse resp =
                            (com.example.starter.web.dto.GeneratorReassignResponse) f.get(15, TimeUnit.SECONDS);
                    success.incrementAndGet();
                    winnerVehicle[0] = resp.newVehicleIds().get(0);
                } catch (Exception e) {
                    assertTrue(e.getCause() instanceof UnprocessableException,
                            "失败方应为业务拒绝: " + e.getCause());
                }
            }
            assertEquals(1, success.get());
            // 恰好一张新单，旧单唯一指向它
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM generator_dispatch_order WHERE status = 'REDISPATCHED'",
                    Integer.class));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM generator_dispatch_order WHERE replaced_by_id IS NOT NULL",
                    Integer.class));
            // 仅胜方车辆被占用，败方车辆不占用
            long loserVehicle = winnerVehicle[0] == n1 ? n2 : n1;
            assertEquals(0, count("generator_occupation",
                    "vehicle_id = ? AND released_at IS NULL", loserVehicle));
            assertEquals(1, count("generator_occupation",
                    "vehicle_id = ? AND released_at IS NULL", winnerVehicle[0]));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 并发到场与正式复电串行后状态一致不泄漏占用() throws Exception {
        freezeAt(S0);
        long o = order("gc-4");
        teamDispatch.dispatch(new DispatchRequest("gc-t1", o, teamId("抢修一班")));
        long g1 = vehicle("gc-v8", "竞A500", 500);
        long g2 = vehicle("gc-v9", "竞B300", 300);
        GeneratorDispatchResponse d = generator.dispatch(new GeneratorDispatchRequest(
                "gc-d4", o, 1, List.of(g1, g2),
                List.of(new LoadItemRequest("市一医院", 800)), U0));
        generator.arrive(new VehicleArrivalRequest("gc-a2", d.orderId(), 1, g1, U0.plusMinutes(2)));
        // 调度确认先完成；现场确认（触发正式复电与撤销）与最后一车到场并发
        restore.confirm(new RestoreConfirmRequest("gc-c1", o, "DISPATCHER", "调度员甲", null));

        freezeAt(S0.plusMinutes(10));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<VehicleArrivalResponse> arrival = pool.submit(() -> {
                ready.countDown();
                start.await();
                return generator.arrive(new VehicleArrivalRequest(
                        "gc-a3", d.orderId(), 2, g2, U0.plusMinutes(10)));
            });
            Future<?> restored = pool.submit(() -> {
                ready.countDown();
                start.await();
                return restore.confirm(new RestoreConfirmRequest("gc-c2", o, "FIELD", "现场员乙",
                        new BigDecimal("800")));
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            VehicleArrivalResponse arrivalResp = arrival.get(15, TimeUnit.SECONDS);
            restored.get(15, TimeUnit.SECONDS);

            // 无论谁先，工单必为 RESTORED，调度单必为 CANCELLED，车辆全部释放，无悬挂占用
            assertEquals("RESTORED", orderStatus(o));
            assertEquals("CANCELLED", generator.viewOrder(d.orderId()).status());
            assertEquals(0, count("generator_occupation",
                    "order_id = ? AND released_at IS NULL", d.orderId()));
            // 若到场先成功则有供电证据（随后撤销保留）；若复电先成功则本次到场仅迟到记事件
            boolean supplied = arrivalResp.supplied();
            int supplyCount = count("generator_supply_record", "order_id = ?", d.orderId());
            assertEquals(supplied ? 1 : 0, supplyCount);
            if (!supplied) {
                assertTrue(arrivalResp.late());
                assertTrue(generator.viewOrder(d.orderId()).events().stream()
                        .anyMatch(e -> e.startsWith("LATE_ARRIVAL")));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 结构化指纹对含分隔符文本与类型差异无歧义() {
        // 朴素 "|" 拼接会令 ["ab|c"] 与 ["ab","c"] 碰撞，结构化编码必须区分
        assertNotEquals(Fingerprints.structured("ab|c"), Fingerprints.structured("ab", "c"));
        // 整数 12 与字符串 "12" 区分
        assertNotEquals(Fingerprints.structured(12), Fingerprints.structured("12"));
        // 布尔与字符串区分
        assertNotEquals(Fingerprints.structured(true), Fingerprints.structured("true"));
        // 同构集合顺序敏感、元素数敏感
        assertNotEquals(Fingerprints.structured(List.of("a", "b")),
                Fingerprints.structured(List.of("b", "a")));
        // 相同输入稳定相等
        assertEquals(Fingerprints.structured("DISPATCH", "r-1", 1L, List.of(2L, 3L), U0),
                Fingerprints.structured("DISPATCH", "r-1", 1L, List.of(2L, 3L), U0));
        assertFalse(Fingerprints.structured("DISPATCH", "r-1", 1L, List.of(2L, 3L), U0)
                .equals(Fingerprints.structured("DISPATCH", "r-1", 2L, List.of(2L, 3L), U0)));
    }
}
