package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.DispatchService;
import com.example.starter.service.FaultReportService;
import com.example.starter.service.GeneratorDispatchService;
import com.example.starter.service.GeneratorTruckService;
import com.example.starter.service.RestoreService;
import com.example.starter.service.TruckReplacementService;
import com.example.starter.service.WorkOrderService;
import com.example.starter.support.AppClock;
import com.example.starter.web.BusinessException;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.dto.ArrivalRequest;
import com.example.starter.web.dto.CreateGeneratorDispatchRequest;
import com.example.starter.web.dto.CreateReplacementRequest;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.GeneratorDispatchView;
import com.example.starter.web.dto.LoadItem;
import com.example.starter.web.dto.MergeRequest;
import com.example.starter.web.dto.RegisterTruckRequest;
import com.example.starter.web.dto.ReassignRequest;
import com.example.starter.web.dto.ReplacementArrivalRequest;
import com.example.starter.web.dto.ReplacementArrivalResponse;
import com.example.starter.web.dto.ReplacementView;
import com.example.starter.web.dto.RestoreConfirmRequest;
import com.example.starter.web.dto.SwitchConfirmRequest;
import com.example.starter.web.dto.SwitchConfirmResponse;
import com.example.starter.web.dto.TruckLedgerEntry;
import com.example.starter.web.dto.WithdrawReplacementRequest;
import com.example.starter.web.dto.WithdrawReplacementResponse;

/**
 * 热备接替：仅SUPPLYING可申请、容量恰等合法、全量预占冲突全单拒绝、每旧车一个未结束接替、
 * 部分到场不可切换、双人确认原子切换并留容量证据、撤回保留旧车、复电/合并关闭接替、
 * 超时改派不可移走预占车、版本409与requestId幂等。
 */
class TruckReplacementServiceTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 26, 10, 0, 0);
    private static final Instant D0 = T0.atZone(AppClock.ZONE).toInstant();

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
    private GeneratorDispatchService dispatches;

    @Autowired
    private TruckReplacementService replacements;

    private int seq;

    private long newOrder() {
        seq++;
        return reports.report(new FaultReportRequest(
                "tr-r-" + seq, "F01", "接替故障点#" + seq, 30, "市一医院", T0)).workOrderId();
    }

    private long registerTruck(int capacityKw) {
        seq++;
        return trucks.register(new RegisterTruckRequest("tr-t-" + seq, capacityKw, "F01")).id();
    }

    private int orderVersion(long orderId) {
        return jdbc.queryForObject("SELECT version FROM work_order WHERE id = ?", Integer.class, orderId);
    }

    /** 建单并下发单车调度、到场转SUPPLYING，返回调度单视图（版本2）。 */
    private GeneratorDispatchView supplyingDispatch(long order, long truckId, int loadKw) {
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "tr-d-" + (++seq), order, (long) orderVersion(order),
                List.of(new LoadItem("市一医院", loadKw)), List.of(truckId), D0));
        dispatches.arrive(created.id(), new ArrivalRequest("tr-da-" + seq, 1L, truckId,
                D0.plusSeconds(300)));
        return dispatches.view(created.id());
    }

    private String replacementStatus(long replacementId) {
        return jdbc.queryForObject("SELECT status FROM truck_replacement WHERE id = ?",
                String.class, replacementId);
    }

    @Test
    void 容量恰等双人确认原子切换并留前后容量证据() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(60);
        long n2 = registerTruck(40);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);

        ReplacementView created = replacements.create(new CreateReplacementRequest(
                "tr-1", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0));
        assertEquals(1, created.version());
        assertEquals("STANDBY", created.status());
        assertEquals(100, created.oldCapacityKw());
        assertNull(created.newCapacityKw());
        assertEquals(supplying.version(), created.dispatchVersion(), "应冻结原调度版本");
        assertEquals(orderVersion(order), created.orderVersion(), "应冻结工单版本");
        assertEquals(2, count("replacement_reservation", "replacement_id = ?", created.id()));
        assertEquals(1, count("active_occupation", "truck_id = ?", oldTruck), "旧车继续供电占用");

        // 台账区分供电占用与热备预占
        List<TruckLedgerEntry> ledger = trucks.ledger();
        assertEquals("SUPPLY", ledger.stream().filter(e -> e.id() == oldTruck).findFirst()
                .orElseThrow().occupationType());
        assertEquals("STANDBY", ledger.stream().filter(e -> e.id() == n1).findFirst()
                .orElseThrow().occupationType());
        assertEquals(created.id(), ledger.stream().filter(e -> e.id() == n2).findFirst()
                .orElseThrow().replacementId());

        ReplacementArrivalResponse a1 = replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tr-1-a1", 1L, n1, D0.plusSeconds(600)));
        assertEquals("ARRIVED", a1.outcome());
        assertEquals(2, a1.version());
        ReplacementArrivalResponse a2 = replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tr-1-a2", 2L, n2, D0.plusSeconds(700)));
        assertEquals("ALL_ARRIVED", a2.outcome());

        SwitchConfirmResponse c1 = replacements.confirmSwitch(created.id(),
                new SwitchConfirmRequest("tr-1-c1", 3L, "操作员甲", D0.plusSeconds(800)));
        assertEquals(false, c1.switched());
        assertEquals("STANDBY", c1.status());
        assertEquals(1, count("active_occupation", "truck_id = ?", oldTruck), "不足两人不释放旧车");

        SwitchConfirmResponse c2 = replacements.confirmSwitch(created.id(),
                new SwitchConfirmRequest("tr-1-c2", 4L, "操作员乙", D0.plusSeconds(900)));
        assertEquals(true, c2.switched());
        assertEquals("SWITCHED", c2.status());
        assertEquals(5, c2.version());
        assertEquals(100, c2.oldCapacityKw());
        assertEquals(100, c2.newCapacityKw(), "60+40恰等100合法");
        assertEquals(T0.plusSeconds(900), c2.switchedAt());

        // 原子替换供电组合并转移占用
        assertEquals(0, count("active_occupation", "truck_id = ?", oldTruck), "旧车已释放");
        assertEquals(0, count("replacement_reservation", "1 = 1"), "预占全部转供电占用");
        assertEquals(supplying.id(), jdbc.queryForObject(
                "SELECT dispatch_id FROM active_occupation WHERE truck_id = ?", Long.class, n1));
        assertEquals(supplying.id(), jdbc.queryForObject(
                "SELECT dispatch_id FROM active_occupation WHERE truck_id = ?", Long.class, n2));
        GeneratorDispatchView after = dispatches.view(supplying.id());
        assertEquals("SUPPLYING", after.status());
        assertEquals(2, after.trucks().size());
        assertTrue(after.trucks().stream().allMatch(t -> t.truckId() == n1 || t.truckId() == n2));
        assertTrue(after.trucks().stream().allMatch(t -> t.arrivedAt() != null), "新车携到场记录入列");
        assertEquals(100, after.totalCapacityKw());
        assertEquals(100, after.totalLoadKw(), "切换后仍覆盖冻结负荷");
        assertEquals(1, count("supply_evidence", "dispatch_id = ?", supplying.id()), "原供电证据保留");

        // 接替完成后旧车才可为另一工单使用
        long orderB = newOrder();
        GeneratorDispatchView second = dispatches.create(new CreateGeneratorDispatchRequest(
                "tr-1-b", orderB, 1L, List.of(new LoadItem("市一医院", 80)), List.of(oldTruck), D0));
        assertEquals("IN_PROGRESS", second.status());
    }

    @Test
    void 容量不足与不同馈线整单拒绝且不留预占() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(60);
        long n2 = registerTruck(30);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> replacements.create(new CreateReplacementRequest(
                        "tr-2", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0)));
        assertTrue(ex.getMessage().contains("不足覆盖"));
        assertEquals(0, count("truck_replacement", "1 = 1"));
        assertEquals(0, count("replacement_reservation", "1 = 1"));
        assertEquals(0, count("gen_idempotency", "request_id = 'tr-2'"), "失败不占键");

        long f02 = jdbc.queryForObject(
                "SELECT id FROM generator_truck WHERE feeder_id = 'F02'", Long.class);
        assertThrows(BusinessException.class,
                () -> replacements.create(new CreateReplacementRequest(
                        "tr-2b", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, f02),
                        D0)), "不同馈线新车应拒绝");
        assertEquals(0, count("replacement_reservation", "1 = 1"));
    }

    @Test
    void 仅供电中调度单可申请且每旧车最多一个未结束接替() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(60);
        long n2 = registerTruck(40);
        long n3 = registerTruck(120);
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "tr-3-d", order, 1L, List.of(new LoadItem("市一医院", 100)), List.of(oldTruck), D0));

        assertThrows(BusinessException.class, () -> replacements.create(new CreateReplacementRequest(
                "tr-3", created.id(), 1L, oldTruck, List.of(n3), D0)), "IN_PROGRESS不可申请接替");

        GeneratorDispatchView supplying = dispatches.view(
                dispatches.arrive(created.id(), new ArrivalRequest("tr-3-a", 1L, oldTruck,
                        D0.plusSeconds(300))).dispatchId());
        replacements.create(new CreateReplacementRequest(
                "tr-3b", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0));
        assertThrows(BusinessException.class, () -> replacements.create(new CreateReplacementRequest(
                "tr-3c", supplying.id(), (long) supplying.version(), oldTruck, List.of(n3), D0)),
                "每辆旧车最多一个未结束接替");
        assertEquals(1, count("active_replacement", "old_truck_id = ?", oldTruck));
        assertEquals(0, count("replacement_reservation", "truck_id = ?", n3), "拒绝后不留预占");
    }

    @Test
    void 任一新车冲突全单拒绝() {
        freezeAt(T0);
        long order = newOrder();
        long orderB = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(60);
        long n2 = registerTruck(40);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);
        // n2被另一工单供电占用
        supplyingDispatch(orderB, n2, 30);

        assertThrows(BusinessException.class, () -> replacements.create(new CreateReplacementRequest(
                "tr-4", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0)));
        assertEquals(0, count("truck_replacement", "1 = 1"));
        assertEquals(0, count("replacement_reservation", "truck_id = ?", n1), "冲突时其余新车不得预占");
        assertEquals(0, count("gen_idempotency", "request_id = 'tr-4'"), "失败不占键");
    }

    @Test
    void 部分到场不可确认切换且同一操作员不可重复确认() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(60);
        long n2 = registerTruck(40);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);
        ReplacementView created = replacements.create(new CreateReplacementRequest(
                "tr-5", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0));

        replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tr-5-a1", 1L, n1, D0.plusSeconds(600)));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> replacements.confirmSwitch(created.id(),
                        new SwitchConfirmRequest("tr-5-c1", 2L, "操作员甲", D0.plusSeconds(700))));
        assertTrue(ex.getMessage().contains("未到齐"), "缺车时不可确认切换");
        assertEquals(2, jdbc.queryForObject("SELECT version FROM truck_replacement WHERE id = ?",
                Integer.class, created.id()), "失败确认不变更版本");
        assertEquals(0, count("replacement_confirmation", "replacement_id = ?", created.id()),
                "缺车确认不落记录");
        assertEquals(0, count("gen_idempotency", "request_id = 'tr-5-c1'"), "失败不占键");

        replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tr-5-a2", 2L, n2, D0.plusSeconds(750)));
        replacements.confirmSwitch(created.id(),
                new SwitchConfirmRequest("tr-5-c2", 3L, "操作员甲", D0.plusSeconds(800)));
        assertThrows(BusinessException.class, () -> replacements.confirmSwitch(created.id(),
                new SwitchConfirmRequest("tr-5-c3", 4L, "操作员甲", D0.plusSeconds(810))),
                "同一操作员不可重复确认");
        assertEquals(1, count("active_occupation", "truck_id = ?", oldTruck), "不足两人不释放旧车");
        assertEquals("STANDBY", replacementStatus(created.id()));
    }

    @Test
    void 未切换可撤回释放新车保留旧车且可再次申请() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(60);
        long n2 = registerTruck(40);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);
        ReplacementView created = replacements.create(new CreateReplacementRequest(
                "tr-6", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0));
        replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tr-6-a1", 1L, n1, D0.plusSeconds(600)));

        WithdrawReplacementResponse withdrawn = replacements.withdraw(created.id(),
                new WithdrawReplacementRequest("tr-6-w", 2L));
        assertEquals("WITHDRAWN", withdrawn.status());
        assertEquals(3, withdrawn.version());
        assertEquals(List.of(Math.min(n1, n2), Math.max(n1, n2)), withdrawn.releasedTruckIds());
        assertEquals(0, count("replacement_reservation", "1 = 1"), "新车预占全部释放");
        assertEquals(1, count("active_occupation", "truck_id = ?", oldTruck), "旧车保留供电");
        assertEquals(1, count("supply_evidence", "dispatch_id = ?", supplying.id()), "供电证据不覆盖");
        assertEquals(1, count("dispatch_truck",
                "dispatch_id = ? AND truck_id = ? AND arrived_at IS NOT NULL", supplying.id(), oldTruck),
                "旧车到场记录不覆盖");
        assertEquals("SUPPLYING", dispatches.view(supplying.id()).status());
        assertEquals(0, count("active_replacement", "1 = 1"));

        assertThrows(BusinessException.class, () -> replacements.withdraw(created.id(),
                new WithdrawReplacementRequest("tr-6-w2", 3L)), "已撤回不可再撤回");

        // 撤回后旧车可再次申请接替
        ReplacementView again = replacements.create(new CreateReplacementRequest(
                "tr-6-b", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0));
        assertEquals("STANDBY", again.status());
    }

    @Test
    void 正式复电关闭未结束接替并释放预占车() {
        freezeAt(T0);
        long order = newOrder();
        dispatch.dispatch(new DispatchRequest("tr-7-d", order, teamId("抢修一班")));
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(60);
        long n2 = registerTruck(40);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);
        ReplacementView created = replacements.create(new CreateReplacementRequest(
                "tr-7", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0));

        restore.confirm(new RestoreConfirmRequest("tr-7-c1", order, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("tr-7-c2", order, "FIELD", "现场员乙",
                new BigDecimal("100")));

        assertEquals("RESTORED", orderStatus(order));
        assertEquals("CLOSED", replacementStatus(created.id()));
        assertEquals(0, count("replacement_reservation", "1 = 1"), "预占车全部释放");
        assertEquals(0, count("active_replacement", "1 = 1"));
        assertEquals(0, count("active_occupation", "1 = 1"), "旧车随原调度单释放");
        assertEquals(1, count("supply_evidence", "dispatch_id = ?", supplying.id()), "供电证据保留");
        assertThrows(BusinessException.class, () -> replacements.withdraw(created.id(),
                new WithdrawReplacementRequest("tr-7-w", 2L)), "复电后不可再操作接替单");
    }

    @Test
    void 工单合并关闭未结束接替() {
        freezeAt(T0);
        long orderA = newOrder();
        long orderB = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(100);
        GeneratorDispatchView supplying = supplyingDispatch(orderA, oldTruck, 100);
        ReplacementView created = replacements.create(new CreateReplacementRequest(
                "tr-8", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1), D0));

        workOrders.merge(new MergeRequest(List.of(orderA, orderB)));

        assertEquals("CLOSED", replacementStatus(created.id()));
        assertEquals(0, count("replacement_reservation", "1 = 1"));
        assertEquals(0, count("active_occupation", "1 = 1"));
    }

    @Test
    void 切换完成后复电不留孤立占用() {
        freezeAt(T0);
        long order = newOrder();
        dispatch.dispatch(new DispatchRequest("tr-9-d", order, teamId("抢修一班")));
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(60);
        long n2 = registerTruck(40);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);
        ReplacementView created = replacements.create(new CreateReplacementRequest(
                "tr-9", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0));
        replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tr-9-a1", 1L, n1, D0.plusSeconds(600)));
        replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tr-9-a2", 2L, n2, D0.plusSeconds(700)));
        replacements.confirmSwitch(created.id(),
                new SwitchConfirmRequest("tr-9-c1", 3L, "操作员甲", D0.plusSeconds(800)));
        replacements.confirmSwitch(created.id(),
                new SwitchConfirmRequest("tr-9-c2", 4L, "操作员乙", D0.plusSeconds(900)));
        assertEquals("SWITCHED", replacementStatus(created.id()));

        restore.confirm(new RestoreConfirmRequest("tr-9-r1", order, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("tr-9-r2", order, "FIELD", "现场员乙",
                new BigDecimal("100")));

        assertEquals("RESTORED", orderStatus(order));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM generator_dispatch WHERE id = ?", String.class, supplying.id()));
        assertEquals(0, count("active_occupation", "1 = 1"), "新车占用随复电释放, 无孤立占用");
        assertEquals(0, count("replacement_reservation", "1 = 1"));
        assertEquals("SWITCHED", replacementStatus(created.id()), "已完成的接替保持切换终态");
        assertEquals(1, count("supply_evidence", "dispatch_id = ?", supplying.id()));
    }

    @Test
    void 超时改派不能移走接替预占车() {
        freezeAt(T0);
        long order = newOrder();
        long orderB = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(100);
        long other = registerTruck(50);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);
        replacements.create(new CreateReplacementRequest(
                "tr-10", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1), D0));

        GeneratorDispatchView timedOut = dispatches.create(new CreateGeneratorDispatchRequest(
                "tr-10-d", orderB, 1L, List.of(new LoadItem("市一医院", 50)), List.of(other), D0));
        freezeAt(T0.plusMinutes(30));
        dispatches.evaluateTimeouts();
        int v = jdbc.queryForObject("SELECT version FROM generator_dispatch WHERE id = ?",
                Integer.class, timedOut.id());

        assertThrows(BusinessException.class, () -> dispatches.reassign(timedOut.id(),
                new ReassignRequest("tr-10-r", (long) v, List.of(n1), D0.plusSeconds(2000))),
                "接替预占车不可被超时改派移走");
        assertEquals(1, count("replacement_reservation", "truck_id = ?", n1), "预占保持有效");
        assertEquals("TIMED_OUT", jdbc.queryForObject(
                "SELECT status FROM generator_dispatch WHERE id = ?", String.class, timedOut.id()));
    }

    @Test
    void 接替单版本冲突409() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(60);
        long n2 = registerTruck(40);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);

        assertThrows(ConflictException.class, () -> replacements.create(new CreateReplacementRequest(
                "tr-11", supplying.id(), 99L, oldTruck, List.of(n1, n2), D0)), "调度单版本不符应409");

        ReplacementView created = replacements.create(new CreateReplacementRequest(
                "tr-11b", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0));
        assertThrows(ConflictException.class, () -> replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tr-11-a", 9L, n1, D0.plusSeconds(600))));
        replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tr-11-a2", 1L, n1, D0.plusSeconds(600)));
        replacements.arrive(created.id(),
                new ReplacementArrivalRequest("tr-11-a3", 2L, n2, D0.plusSeconds(700)));
        assertThrows(ConflictException.class, () -> replacements.confirmSwitch(created.id(),
                new SwitchConfirmRequest("tr-11-c", 1L, "操作员甲", D0.plusSeconds(800))));
        assertThrows(ConflictException.class, () -> replacements.withdraw(created.id(),
                new WithdrawReplacementRequest("tr-11-w", 1L)));
        assertThrows(NotFoundException.class, () -> replacements.view(99999L));
    }

    @Test
    void 接替幂等重放异参409且失败不占键() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck(100);
        long n1 = registerTruck(60);
        long n2 = registerTruck(40);
        GeneratorDispatchView supplying = supplyingDispatch(order, oldTruck, 100);

        // 失败请求不占键：先因容量不足失败，同键修正参数后成功
        assertThrows(BusinessException.class, () -> replacements.create(new CreateReplacementRequest(
                "tr-12", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1), D0)));
        ReplacementView first = replacements.create(new CreateReplacementRequest(
                "tr-12", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0));
        ReplacementView replay = replacements.create(new CreateReplacementRequest(
                "tr-12", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1, n2), D0));
        assertEquals(first, replay, "同键同参应重放首次冻结结果");
        assertEquals(1, count("truck_replacement", "1 = 1"));
        assertThrows(ConflictException.class, () -> replacements.create(new CreateReplacementRequest(
                "tr-12", supplying.id(), (long) supplying.version(), oldTruck, List.of(n1), D0)),
                "同键异参应409");

        // 到场幂等重放
        ReplacementArrivalResponse a1 = replacements.arrive(first.id(),
                new ReplacementArrivalRequest("tr-12-a1", 1L, n1, D0.plusSeconds(600)));
        ReplacementArrivalResponse a1Replay = replacements.arrive(first.id(),
                new ReplacementArrivalRequest("tr-12-a1", 1L, n1, D0.plusSeconds(600)));
        assertEquals(a1, a1Replay);
        assertEquals(1, count("replacement_truck",
                "replacement_id = ? AND truck_id = ? AND arrived_at IS NOT NULL", first.id(), n1));

        // 切换确认幂等重放：同键同参重放而非判同一操作员重复
        replacements.arrive(first.id(),
                new ReplacementArrivalRequest("tr-12-a2", 2L, n2, D0.plusSeconds(700)));
        SwitchConfirmResponse c1 = replacements.confirmSwitch(first.id(),
                new SwitchConfirmRequest("tr-12-c1", 3L, "操作员甲", D0.plusSeconds(800)));
        SwitchConfirmResponse c1Replay = replacements.confirmSwitch(first.id(),
                new SwitchConfirmRequest("tr-12-c1", 3L, "操作员甲", D0.plusSeconds(800)));
        assertEquals(c1, c1Replay, "确认同键同参应重放");
        assertEquals(1, count("replacement_confirmation", "replacement_id = ?", first.id()));
        assertThrows(ConflictException.class, () -> replacements.confirmSwitch(first.id(),
                new SwitchConfirmRequest("tr-12-c1", 3L, "操作员丙", D0.plusSeconds(800))),
                "确认同键异参应409");

        // 撤回幂等重放
        WithdrawReplacementResponse w1 = replacements.withdraw(first.id(),
                new WithdrawReplacementRequest("tr-12-w", 4L));
        WithdrawReplacementResponse w1Replay = replacements.withdraw(first.id(),
                new WithdrawReplacementRequest("tr-12-w", 4L));
        assertEquals(w1, w1Replay);
        assertEquals("WITHDRAWN", replacementStatus(first.id()));
    }
}
