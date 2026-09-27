package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import com.example.starter.service.WorkOrderService;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.GeneratorDispatchRequest;
import com.example.starter.web.dto.GeneratorDispatchView;
import com.example.starter.web.dto.MergeRequest;
import com.example.starter.web.dto.ReassignRequest;
import com.example.starter.web.dto.RestoreConfirmRequest;
import com.example.starter.web.dto.TruckArrivalRequest;
import com.example.starter.web.dto.TruckArrivalResponse;
import com.example.starter.web.dto.TruckRegisterRequest;

/**
 * 发电车组合调度：冻结负荷/工单版本、容量覆盖（恰等合法）、一车一占用、30分钟到达超时（恰满触发）、
 * 逐车到场全部确认才供电、整体改派原子切换、复电/合并撤销释放车辆、幂等与并发边界。
 */
class GeneratorDispatchServiceTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);

    @Autowired
    private FaultReportService reports;

    @Autowired
    private DispatchService dispatch;

    @Autowired
    private RestoreService restore;

    @Autowired
    private WorkOrderService workOrders;

    @Autowired
    private GeneratorTruckService trucks;

    @Autowired
    private GeneratorDispatchService generator;

    private long orderOnF01(String requestId, String point) {
        return reports.report(new FaultReportRequest(requestId, "F01", point, 30, "市一医院", T0))
                .workOrderId();
    }

    private long truck(String requestId, String code, int kw, String feeder) {
        return trucks.register(new TruckRegisterRequest(requestId, code, kw, feeder, null)).truckId();
    }

    private long occupationCount(long truckId) {
        return count("generator_dispatch_truck", "truck_id = ? AND active_occupation IS NOT NULL", truckId);
    }

    @Test
    void 组合调度冻结负荷与工单版本且容量恰等合法() {
        freezeAt(T0);
        long order = orderOnF01("gd-r-1", "组合点A");
        long ta = truck("gd-t-1", "GD-A1", 600, "F01");
        long tb = truck("gd-t-2", "GD-A2", 600, "F01");

        GeneratorDispatchView view = generator.create(
                new GeneratorDispatchRequest("gd-1", order, 1L, List.of(tb, ta)));
        assertEquals("DISPATCHED", view.status());
        assertEquals(1, view.version());
        assertEquals(1200, view.totalLoadKw(), "F01重要用户负荷合计400+800");
        assertEquals(1, view.workOrderVersion());
        assertEquals(T0, view.dispatchedAt());
        assertEquals(2, view.loads().size());
        assertEquals(2, view.trucks().size());
        assertEquals(1, occupationCount(ta));
        assertEquals(1, occupationCount(tb));

        GeneratorDispatchView replay = generator.create(
                new GeneratorDispatchRequest("gd-1", order, 1L, List.of(ta, tb)));
        assertTrue(replay.replayed(), "同键同参（集合顺序无关）应重放首次冻结结果");
        assertEquals(view.dispatchId(), replay.dispatchId());
        assertEquals(1, count("generator_dispatch", "work_order_id = ?", order));

        assertThrows(ConflictException.class, () -> generator.create(
                new GeneratorDispatchRequest("gd-1", order, 1L, List.of(ta))));
        assertEquals(1, count("generator_dispatch", "work_order_id = ?", order));
    }

    @Test
    void 容量不足422且失败不占幂等键() {
        freezeAt(T0);
        long order = orderOnF01("gd-r-2", "组合点B");
        long ta = truck("gd-t-3", "GD-B1", 600, "F01");
        long tb = truck("gd-t-4", "GD-B2", 600, "F01");

        assertThrows(UnprocessableException.class, () -> generator.create(
                new GeneratorDispatchRequest("gd-2", order, 1L, List.of(ta))));
        assertEquals(0, count("generator_idempotency", "request_id = 'gd-2'"), "失败不占键");
        assertEquals(0, count("generator_dispatch", "work_order_id = ?", order));

        GeneratorDispatchView ok = generator.create(
                new GeneratorDispatchRequest("gd-2", order, 1L, List.of(ta, tb)));
        assertEquals("DISPATCHED", ok.status(), "补足容量后同键应可成功");
    }

    @Test
    void 任一车冲突整单不派() {
        freezeAt(T0);
        long order1 = orderOnF01("gd-r-3", "组合点C");
        long order2 = orderOnF01("gd-r-4", "组合点D");
        long ta = truck("gd-t-5", "GD-C1", 1200, "F01");
        long tb = truck("gd-t-6", "GD-C2", 1200, "F01");

        generator.create(new GeneratorDispatchRequest("gd-3", order1, 1L, List.of(ta)));
        assertThrows(UnprocessableException.class, () -> generator.create(
                new GeneratorDispatchRequest("gd-4", order2, 1L, List.of(ta, tb))));
        assertEquals(0, count("generator_dispatch", "work_order_id = ?", order2), "整单不派");
        assertEquals(0, occupationCount(tb), "未占用车辆不应被冻结");
    }

    @Test
    void 调度前置校验版本馈线可用性与工单状态() {
        freezeAt(T0);
        long order = orderOnF01("gd-r-5", "组合点E");
        long ta = truck("gd-t-7", "GD-D1", 1200, "F01");
        long f02Truck = truck("gd-t-8", "GD-D2", 1200, "F02");
        trucks.update(ta, new com.example.starter.web.dto.TruckUpdateRequest("gd-t-9", 1L, null, false));

        assertThrows(ConflictException.class, () -> generator.create(
                new GeneratorDispatchRequest("gd-5", order, 99L, List.of(ta))), "工单版本不符应409");
        assertThrows(NotFoundException.class, () -> generator.create(
                new GeneratorDispatchRequest("gd-6", 999999L, 1L, List.of(ta))));
        assertThrows(UnprocessableException.class, () -> generator.create(
                new GeneratorDispatchRequest("gd-7", order, 1L, List.of(ta))), "停用车辆应422");
        assertThrows(UnprocessableException.class, () -> generator.create(
                new GeneratorDispatchRequest("gd-8", order, 1L, List.of(f02Truck))), "馈线不一致应422");

        long emptyFeederOrder = reports.report(new FaultReportRequest(
                "gd-r-6", "F09", "无重要用户台区", 10, null, T0)).workOrderId();
        assertThrows(UnprocessableException.class, () -> generator.create(
                new GeneratorDispatchRequest("gd-9", emptyFeederOrder, 1L, List.of(f02Truck))),
                "无重要用户负荷应422");

        // 已复电工单不可调度发电车
        long restored = orderOnF01("gd-r-7", "组合点F");
        dispatch.dispatch(new DispatchRequest("gd-d-1", restored, teamId("抢修一班")));
        restore.confirm(new RestoreConfirmRequest("gd-c-1", restored, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("gd-c-2", restored, "FIELD", "现场员乙",
                new BigDecimal("100")));
        long version = jdbc.queryForObject("SELECT version FROM work_order WHERE id = ?",
                Integer.class, restored);
        assertThrows(UnprocessableException.class, () -> generator.create(
                new GeneratorDispatchRequest("gd-10", restored, version, List.of(f02Truck))));
    }

    @Test
    void 到场逐车唯一记录全部确认才供电() {
        freezeAt(T0);
        long order = orderOnF01("gd-r-8", "组合点G");
        long ta = truck("gd-t-10", "GD-E1", 600, "F01");
        long tb = truck("gd-t-11", "GD-E2", 600, "F01");
        long tc = truck("gd-t-12", "GD-E3", 600, "F01");
        GeneratorDispatchView created = generator.create(
                new GeneratorDispatchRequest("gd-11", order, 1L, List.of(ta, tb)));

        freezeAt(T0.plusMinutes(10));
        TruckArrivalResponse first = generator.arrive(created.dispatchId(),
                new TruckArrivalRequest("gd-a-1", 1L, ta, T0.plusMinutes(10)));
        assertEquals("DISPATCHED", first.status());
        assertFalse(first.allArrived());
        assertFalse(first.supplying(), "部分到场不算全负荷恢复");
        assertEquals(2, first.version());
        assertNull(jdbc.queryForObject("SELECT supplied_at FROM generator_dispatch WHERE id = ?",
                java.sql.Timestamp.class, created.dispatchId()), "未全部到场不应供电");

        TruckArrivalResponse replay = generator.arrive(created.dispatchId(),
                new TruckArrivalRequest("gd-a-1", 1L, ta, T0.plusMinutes(10)));
        assertTrue(replay.replayed());
        assertEquals(1, count("generator_dispatch_truck",
                "dispatch_id = ? AND truck_id = ? AND arrived_at IS NOT NULL",
                created.dispatchId(), ta), "重放不应重复记录");

        assertThrows(UnprocessableException.class, () -> generator.arrive(created.dispatchId(),
                new TruckArrivalRequest("gd-a-2", 2L, ta, T0.plusMinutes(10))), "重复到场应422");
        assertThrows(UnprocessableException.class, () -> generator.arrive(created.dispatchId(),
                new TruckArrivalRequest("gd-a-3", 2L, tc, T0.plusMinutes(10))), "方案外车辆应422");
        assertThrows(IllegalArgumentException.class, () -> generator.arrive(created.dispatchId(),
                new TruckArrivalRequest("gd-a-4", 2L, tb, T0.plusMinutes(20))), "未来到场时刻应400");

        freezeAt(T0.plusMinutes(15));
        TruckArrivalResponse second = generator.arrive(created.dispatchId(),
                new TruckArrivalRequest("gd-a-5", 2L, tb, T0.plusMinutes(15)));
        assertEquals("SUPPLYING", second.status());
        assertTrue(second.allArrived());
        assertTrue(second.supplying());
        assertEquals(3, second.version());
        assertNotNull(generator.view(created.dispatchId()).suppliedAt());
    }

    @Test
    void 下发恰满30分钟未全到场转到达超时() {
        freezeAt(T0);
        long order = orderOnF01("gd-r-9", "组合点H");
        long ta = truck("gd-t-13", "GD-F1", 1200, "F01");
        GeneratorDispatchView created = generator.create(
                new GeneratorDispatchRequest("gd-12", order, 1L, List.of(ta)));

        freezeAt(T0.plusMinutes(29).plusSeconds(59));
        assertTrue(generator.timeoutCheck().isEmpty(), "未满30分钟不应超时");
        assertEquals("DISPATCHED", generator.view(created.dispatchId()).status());

        freezeAt(T0.plusMinutes(30));
        assertEquals(List.of(created.dispatchId()), generator.timeoutCheck(), "恰满30分钟应触发超时");
        GeneratorDispatchView timedOut = generator.view(created.dispatchId());
        assertEquals("ARRIVAL_TIMEOUT", timedOut.status());
        assertEquals(2, timedOut.version());
        assertTrue(generator.timeoutCheck().isEmpty(), "超时不应重复转换");
        assertEquals(1, count("generator_dispatch_event",
                "dispatch_id = ? AND event_type = 'TIMEOUT'", created.dispatchId()));
    }

    @Test
    void 超时后迟到到场只记事件不再占车() {
        freezeAt(T0);
        long order = orderOnF01("gd-r-10", "组合点I");
        long ta = truck("gd-t-14", "GD-G1", 600, "F01");
        long tb = truck("gd-t-15", "GD-G2", 600, "F01");
        GeneratorDispatchView created = generator.create(
                new GeneratorDispatchRequest("gd-13", order, 1L, List.of(ta, tb)));
        freezeAt(T0.plusMinutes(10));
        generator.arrive(created.dispatchId(), new TruckArrivalRequest("gd-a-6", 1L, ta, T0.plusMinutes(10)));

        freezeAt(T0.plusMinutes(31));
        assertEquals(List.of(created.dispatchId()), generator.timeoutCheck());

        TruckArrivalResponse late = generator.arrive(created.dispatchId(),
                new TruckArrivalRequest("gd-a-7", 3L, tb, T0.plusMinutes(31)));
        assertTrue(late.late());
        assertEquals("ARRIVAL_TIMEOUT", late.status());
        assertFalse(late.supplying());
        assertEquals(3, late.version(), "迟到只记事件，版本不变");
        assertEquals(1, count("generator_dispatch_event",
                "dispatch_id = ? AND event_type = 'LATE_ARRIVAL'", created.dispatchId()));
        assertEquals(0, count("generator_dispatch_truck",
                "dispatch_id = ? AND truck_id = ? AND arrived_at IS NOT NULL", created.dispatchId(), tb),
                "迟到到场不应补记到场");
    }

    @Test
    void 超时整体改派保留到场车并原子切换() {
        freezeAt(T0);
        long order = orderOnF01("gd-r-11", "组合点J");
        long ta = truck("gd-t-16", "GD-H1", 600, "F01");
        long tb = truck("gd-t-17", "GD-H2", 600, "F01");
        long tc = truck("gd-t-18", "GD-H3", 600, "F01");
        GeneratorDispatchView created = generator.create(
                new GeneratorDispatchRequest("gd-14", order, 1L, List.of(ta, tb)));
        freezeAt(T0.plusMinutes(10));
        generator.arrive(created.dispatchId(), new TruckArrivalRequest("gd-a-8", 1L, ta, T0.plusMinutes(10)));
        freezeAt(T0.plusMinutes(30));
        generator.timeoutCheck();

        GeneratorDispatchView reassigned = generator.reassign(created.dispatchId(),
                new ReassignRequest("gd-15", 3L, List.of(tc)));
        assertEquals("DISPATCHED", reassigned.status());
        assertEquals(1, reassigned.version());
        assertEquals(created.dispatchId(), reassigned.predecessorId());
        assertEquals(1200, reassigned.totalLoadKw(), "改派继承冻结负荷");
        assertEquals(2, reassigned.trucks().size());
        assertEquals(T0.plusMinutes(10), reassigned.trucks().stream()
                .filter(t -> t.truckId() == ta).findFirst().orElseThrow().arrivedAt(),
                "已到场车保留到场记录");
        assertNull(reassigned.trucks().stream()
                .filter(t -> t.truckId() == tc).findFirst().orElseThrow().arrivedAt());

        GeneratorDispatchView old = generator.view(created.dispatchId());
        assertEquals("REASSIGNED", old.status());
        assertEquals(4, old.version());
        assertEquals(0, occupationCount(tb), "未到场车应释放");
        assertEquals(1, occupationCount(ta), "已到场车占用转移到新方案");
        assertEquals(1, occupationCount(tc));
        assertEquals(reassigned.dispatchId(), jdbc.queryForObject(
                "SELECT dispatch_id FROM generator_dispatch_truck WHERE active_occupation = ?",
                Long.class, ta), "已到场车占用应归属新调度单");

        GeneratorDispatchView replay = generator.reassign(created.dispatchId(),
                new ReassignRequest("gd-15", 3L, List.of(tc)));
        assertTrue(replay.replayed());
        assertEquals(reassigned.dispatchId(), replay.dispatchId());
        assertEquals(2, count("generator_dispatch", "work_order_id = ?", order), "重放不应生成新单");

        // 旧单迟到到场只记事件；新单新车到场后全部确认供电
        freezeAt(T0.plusMinutes(40));
        TruckArrivalResponse late = generator.arrive(created.dispatchId(),
                new TruckArrivalRequest("gd-a-9", 4L, tb, T0.plusMinutes(40)));
        assertTrue(late.late());
        assertEquals("REASSIGNED", late.status());
        TruckArrivalResponse arrived = generator.arrive(reassigned.dispatchId(),
                new TruckArrivalRequest("gd-a-10", 1L, tc, T0.plusMinutes(40)));
        assertEquals("SUPPLYING", arrived.status());
        assertTrue(arrived.supplying());
    }

    @Test
    void 改派前置校验与容量约束() {
        freezeAt(T0);
        long order = orderOnF01("gd-r-12", "组合点K");
        long ta = truck("gd-t-19", "GD-I1", 1200, "F01");
        long tb = truck("gd-t-20", "GD-I2", 500, "F01");
        GeneratorDispatchView created = generator.create(
                new GeneratorDispatchRequest("gd-16", order, 1L, List.of(ta)));

        assertThrows(UnprocessableException.class, () -> generator.reassign(created.dispatchId(),
                new ReassignRequest("gd-17", 1L, List.of(tb))), "未超时不应改派");

        freezeAt(T0.plusMinutes(30));
        generator.timeoutCheck();
        assertThrows(ConflictException.class, () -> generator.reassign(created.dispatchId(),
                new ReassignRequest("gd-18", 1L, List.of(tb))), "版本不符应409");
        assertThrows(UnprocessableException.class, () -> generator.reassign(created.dispatchId(),
                new ReassignRequest("gd-19", 2L, List.of(tb))), "新方案容量不足应422");
        assertThrows(UnprocessableException.class, () -> generator.reassign(created.dispatchId(),
                new ReassignRequest("gd-20", 2L, List.of(ta))), "换入车已在原方案应422");
        assertEquals(0, count("generator_idempotency",
                "request_id IN ('gd-17','gd-18','gd-19','gd-20')"), "失败不占键");
    }

    @Test
    void 正式复电原子撤销未结束调度并保留供电证据() {
        freezeAt(T0);
        long order = orderOnF01("gd-r-13", "组合点L");
        dispatch.dispatch(new DispatchRequest("gd-d-2", order, teamId("抢修一班")));
        long ta = truck("gd-t-21", "GD-J1", 600, "F01");
        long tb = truck("gd-t-22", "GD-J2", 600, "F01");
        GeneratorDispatchView created = generator.create(
                new GeneratorDispatchRequest("gd-21", order, 2L, List.of(ta, tb)));
        freezeAt(T0.plusMinutes(5));
        generator.arrive(created.dispatchId(), new TruckArrivalRequest("gd-a-11", 1L, ta, T0.plusMinutes(5)));
        generator.arrive(created.dispatchId(), new TruckArrivalRequest("gd-a-12", 2L, tb, T0.plusMinutes(5)));
        assertEquals("SUPPLYING", generator.view(created.dispatchId()).status());

        freezeAt(T0.plusMinutes(20));
        restore.confirm(new RestoreConfirmRequest("gd-c-3", order, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("gd-c-4", order, "FIELD", "现场员乙",
                new BigDecimal("1200")));

        GeneratorDispatchView cancelled = generator.view(created.dispatchId());
        assertEquals("CANCELLED", cancelled.status());
        assertNotNull(cancelled.suppliedAt(), "供电证据应保留");
        assertEquals(2, cancelled.trucks().stream().filter(t -> t.arrivedAt() != null).count(),
                "到场证据应保留");
        assertEquals(0, occupationCount(ta));
        assertEquals(0, occupationCount(tb));
        assertEquals(1, count("generator_dispatch_event",
                "dispatch_id = ? AND event_type = 'CANCELLED'", created.dispatchId()));
        assertEquals("RESTORED", orderStatus(order), "临时保电不代替双确认复电，工单按原流程复电");
    }

    @Test
    void 工单合并原子撤销未结束调度() {
        freezeAt(T0);
        long order1 = orderOnF01("gd-r-14", "组合点M");
        long order2 = orderOnF01("gd-r-15", "组合点N");
        long ta = truck("gd-t-23", "GD-K1", 1200, "F01");
        GeneratorDispatchView created = generator.create(
                new GeneratorDispatchRequest("gd-22", order1, 1L, List.of(ta)));

        workOrders.merge(new MergeRequest(List.of(order1, order2)));
        assertEquals("CANCELLED", generator.view(created.dispatchId()).status());
        assertEquals(0, occupationCount(ta));
        assertEquals("MERGED", orderStatus(order1));
    }

    @Test
    void 并发创建占用同一车仅一单成功() throws Exception {
        freezeAt(T0);
        long order1 = orderOnF01("gd-r-16", "组合点O");
        long order2 = orderOnF01("gd-r-17", "组合点P");
        long ta = truck("gd-t-24", "GD-L1", 1200, "F01");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Object> f1 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                return createOrError("gd-23", order1, ta);
            });
            Future<Object> f2 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                return createOrError("gd-24", order2, ta);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            Object r1 = f1.get(10, TimeUnit.SECONDS);
            Object r2 = f2.get(10, TimeUnit.SECONDS);
            long successes = List.of(r1, r2).stream().filter(r -> r instanceof GeneratorDispatchView).count();
            long conflicts = List.of(r1, r2).stream().filter(r -> r instanceof UnprocessableException).count();
            assertEquals(1, successes, "应恰有一单派车成功: " + List.of(r1, r2));
            assertEquals(1, conflicts, "另一单应因车辆占用422: " + List.of(r1, r2));
            assertEquals(1, occupationCount(ta), "一车只一个未结束占用");
        } finally {
            pool.shutdownNow();
        }
    }

    private Object createOrError(String requestId, long order, long truckId) {
        try {
            return generator.create(new GeneratorDispatchRequest(requestId, order, 1L, List.of(truckId)));
        } catch (UnprocessableException e) {
            return e;
        }
    }

    @Test
    void 并发同车到场仅一条记录() throws Exception {
        freezeAt(T0);
        long order = orderOnF01("gd-r-18", "组合点Q");
        long ta = truck("gd-t-25", "GD-M1", 600, "F01");
        long tb = truck("gd-t-26", "GD-M2", 600, "F01");
        GeneratorDispatchView created = generator.create(
                new GeneratorDispatchRequest("gd-25", order, 1L, List.of(ta, tb)));
        freezeAt(T0.plusMinutes(5));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Object> f1 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                return arriveOrError(created.dispatchId(), "gd-a-13", ta);
            });
            Future<Object> f2 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                return arriveOrError(created.dispatchId(), "gd-a-14", ta);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            Object r1 = f1.get(10, TimeUnit.SECONDS);
            Object r2 = f2.get(10, TimeUnit.SECONDS);
            long successes = List.of(r1, r2).stream().filter(r -> r instanceof TruckArrivalResponse).count();
            assertEquals(1, successes, "同车并发到场应恰有一条成功: " + List.of(r1, r2));
            assertEquals(1, count("generator_dispatch_truck",
                    "dispatch_id = ? AND truck_id = ? AND arrived_at IS NOT NULL", created.dispatchId(), ta));
        } finally {
            pool.shutdownNow();
        }
    }

    private Object arriveOrError(long dispatchId, String requestId, long truckId) {
        try {
            return generator.arrive(dispatchId,
                    new TruckArrivalRequest(requestId, 1L, truckId, T0.plusMinutes(5)));
        } catch (ConflictException | UnprocessableException e) {
            return e;
        }
    }

    @Test
    void 并发改派与迟到到场终态一致() throws Exception {
        freezeAt(T0);
        long order = orderOnF01("gd-r-19", "组合点R");
        long ta = truck("gd-t-27", "GD-N1", 600, "F01");
        long tb = truck("gd-t-28", "GD-N2", 600, "F01");
        long tc = truck("gd-t-29", "GD-N3", 600, "F01");
        GeneratorDispatchView created = generator.create(
                new GeneratorDispatchRequest("gd-26", order, 1L, List.of(ta, tb)));
        freezeAt(T0.plusMinutes(10));
        generator.arrive(created.dispatchId(), new TruckArrivalRequest("gd-a-15", 1L, ta, T0.plusMinutes(10)));
        freezeAt(T0.plusMinutes(30));
        generator.timeoutCheck();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Object> f1 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                try {
                    return generator.reassign(created.dispatchId(), new ReassignRequest("gd-27", 3L, List.of(tc)));
                } catch (ConflictException | UnprocessableException e) {
                    return e;
                }
            });
            Future<Object> f2 = pool.submit(() -> {
                ready.countDown();
                assertTrue(go.await(5, TimeUnit.SECONDS));
                try {
                    return generator.arrive(created.dispatchId(),
                            new TruckArrivalRequest("gd-a-16", 3L, tb, T0.plusMinutes(30)));
                } catch (ConflictException | UnprocessableException e) {
                    return e;
                }
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            Object r1 = f1.get(10, TimeUnit.SECONDS);
            Object r2 = f2.get(10, TimeUnit.SECONDS);
            assertTrue(r1 instanceof GeneratorDispatchView, "改派应成功: " + r1);
            GeneratorDispatchView newDispatch = (GeneratorDispatchView) r1;
            assertEquals("REASSIGNED", generator.view(created.dispatchId()).status());
            if (r2 instanceof TruckArrivalResponse late) {
                assertTrue(late.late(), "到场若执行只能是迟到事件");
            }
            assertEquals(0, occupationCount(tb), "旧方案未到场车应释放");
            assertEquals(1, occupationCount(tc));
            assertEquals(1, count("generator_dispatch", "predecessor_id = ?", created.dispatchId()),
                    "改派只生成一张新单");
            assertEquals(newDispatch.dispatchId(), jdbc.queryForObject(
                    "SELECT id FROM generator_dispatch WHERE predecessor_id = ?", Long.class,
                    created.dispatchId()));
        } finally {
            pool.shutdownNow();
        }
    }
}
