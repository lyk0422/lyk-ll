package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import com.example.starter.service.StandbyReplacementService;
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
import com.example.starter.web.dto.ReassignRequest;
import com.example.starter.web.dto.ReplacementArrivalRequest;
import com.example.starter.web.dto.ReplacementArrivalResponse;
import com.example.starter.web.dto.ReplacementRevokeRequest;
import com.example.starter.web.dto.ReplacementRevokeResponse;
import com.example.starter.web.dto.ReplacementSwitchRequest;
import com.example.starter.web.dto.ReplacementSwitchResponse;
import com.example.starter.web.dto.ReplacementView;
import com.example.starter.web.dto.RegisterTruckRequest;
import com.example.starter.web.dto.RestoreConfirmRequest;
import com.example.starter.web.dto.TruckLedgerEntry;

/**
 * 热备接替：容量恰等（100kW旧车由60+40kW热备替换）、全量预占与台账区分、逐辆到场与双人确认、
 * 不足两人或缺车不释放旧车、未切换撤回、版本与幂等、超时改派不得移走预占车、复电/合并关闭接替。
 */
class StandbyReplacementServiceTest extends AbstractRepairTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 27, 10, 0, 0);
    private static final Instant D0 = T0.atZone(AppClock.ZONE).toInstant();

    @Autowired
    private FaultReportService reports;

    @Autowired
    private DispatchService teamDispatch;

    @Autowired
    private RestoreService restore;

    @Autowired
    private WorkOrderService workOrders;

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
                "sr-r-" + seq, "F01", "热备故障点#" + seq, 30, "市一医院", T0)).workOrderId();
    }

    private long registerTruck(String reqId, int capacityKw, String feeder) {
        return trucks.register(new RegisterTruckRequest(reqId, capacityKw, feeder)).id();
    }

    /** 以给定车辆创建调度单并逐辆到场，返回 SUPPLYING 调度单。 */
    private GeneratorDispatchView supplying(long order, String reqPrefix, int loadKw, List<Long> truckIds) {
        GeneratorDispatchView d = dispatches.create(new CreateGeneratorDispatchRequest(
                reqPrefix + "-c", order, (long) orderVersion(order),
                List.of(new LoadItem("市一医院", loadKw)), truckIds, D0));
        for (int i = 0; i < truckIds.size(); i++) {
            long truckId = truckIds.get(i);
            dispatches.arrive(d.id(), new ArrivalRequest(
                    reqPrefix + "-a" + i, (long) (d.version() + i), truckId,
                    D0.plusSeconds(300L + i)));
        }
        return dispatches.view(d.id());
    }

    private int orderVersion(long orderId) {
        return jdbc.queryForObject("SELECT version FROM work_order WHERE id = ?", Integer.class, orderId);
    }

    private String occupationType(long truckId) {
        return jdbc.queryForObject("SELECT occupation_type FROM active_occupation WHERE truck_id = ?",
                String.class, truckId);
    }

    private Long occupationDispatch(long truckId) {
        return jdbc.queryForObject("SELECT dispatch_id FROM active_occupation WHERE truck_id = ?",
                Long.class, truckId);
    }

    private List<Long> compositionTruckIds(long dispatchId) {
        return jdbc.query(
                "SELECT truck_id FROM dispatch_truck WHERE dispatch_id = ? ORDER BY truck_id",
                (rs, n) -> rs.getLong(1), dispatchId);
    }

    @Test
    void 容量恰等_100旧车由60加40热备替换_到齐双确认原子切换() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck("sr-1-old", 100, "F01");
        long t60 = registerTruck("sr-1-60", 60, "F01");
        long t40 = registerTruck("sr-1-40", 40, "F01");
        GeneratorDispatchView d = supplying(order, "sr-1", 100, List.of(oldTruck));

        ReplacementView r = replacements.create(d.id(), new CreateReplacementRequest(
                "sr-1-r", (long) d.version(), oldTruck, List.of(t60, t40), D0.plusSeconds(600)));

        assertEquals(1, r.version());
        assertEquals("WAITING", r.status());
        assertEquals(100, r.frozenLoadKw(), "冻结负荷");
        assertEquals(d.orderVersion(), r.frozenOrderVersion(), "冻结原调度版本");
        assertEquals(100, r.oldCapacityKw());
        assertEquals(100, r.newCapacityKw(), "新车容量之和恰等旧车");
        assertEquals(2, r.newTrucks().size());
        assertNull(r.evidence());
        // 新车全量热备预占，旧车继续供电
        assertEquals("STANDBY", occupationType(t60));
        assertEquals("STANDBY", occupationType(t40));
        assertEquals("SUPPLY", occupationType(oldTruck));
        assertEquals(d.id(), occupationDispatch(oldTruck));

        ReplacementArrivalResponse a60 = replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-1-a60", 1L, t60, D0.plusSeconds(900)));
        assertEquals("ARRIVED", a60.outcome());
        assertEquals(2, a60.version(), "部分到场版本加1但不切换");
        assertEquals("WAITING", a60.status());
        assertEquals("SUPPLY", occupationType(oldTruck), "缺车时不释放旧车");

        ReplacementArrivalResponse a40 = replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-1-a40", 2L, t40, D0.plusSeconds(1200)));
        assertEquals("ALL_ARRIVED", a40.outcome(), "到齐但无确认仍不切换");
        assertEquals(3, a40.version());
        assertEquals("SUPPLY", occupationType(oldTruck), "无人确认不释放旧车");

        ReplacementSwitchResponse c1 = replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest("sr-1-op1", 3L, "操作员甲", D0.plusSeconds(1300)));
        assertFalse(c1.switched(), "单人不切换");
        assertEquals(4, c1.version(), "确认登记也是修改, 版本加1");
        assertEquals("SUPPLY", occupationType(oldTruck), "单人不释放旧车");

        ReplacementSwitchResponse c2 = replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest("sr-1-op2", 4L, "操作员乙", D0.plusSeconds(1400)));
        assertTrue(c2.switched(), "两名不同操作员且到齐应原子切换");
        assertEquals("SWITCHED", c2.status());
        assertEquals(5, c2.version());
        assertEquals(List.of(oldTruck), c2.evidence().beforeTruckIds());
        assertEquals(100, c2.evidence().beforeCapacityKw());
        assertEquals(List.of(t60, t40), c2.evidence().afterTruckIds());
        assertEquals(100, c2.evidence().afterCapacityKw(), "切换后容量恰等覆盖冻结负荷");

        // 占用转移：新车供电，旧车释放可为另一工单使用
        assertEquals(0, count("active_occupation", "truck_id = ?", oldTruck),
                "切换完成后旧车应已释放");
        assertEquals("SUPPLY", occupationType(t60));
        assertEquals("SUPPLY", occupationType(t40));
        assertEquals(d.id(), occupationDispatch(t60));
        assertEquals(List.of(t60, t40), compositionTruckIds(d.id()), "调度单供电组合被原子替换");
        assertEquals("SUPPLYING", jdbc.queryForObject(
                "SELECT status FROM generator_dispatch WHERE id = ?", String.class, d.id()));
        assertEquals(1, count("replacement_switch_evidence", "replacement_id = ? AND frozen_load_kw = 100",
                r.id()));

        // 旧车可为另一工单使用
        long order2 = newOrder();
        GeneratorDispatchView d2 = dispatches.create(new CreateGeneratorDispatchRequest(
                "sr-1-d2", order2, 1L, List.of(new LoadItem("市一医院", 80)), List.of(oldTruck),
                D0.plusSeconds(1500)));
        assertEquals(d2.id(), occupationDispatch(oldTruck));
    }

    @Test
    void 台账区分供电占用与热备预占() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck("sr-2-old", 120, "F01");
        long standby = registerTruck("sr-2-new", 120, "F01");
        GeneratorDispatchView d = supplying(order, "sr-2", 100, List.of(oldTruck));
        replacements.create(d.id(), new CreateReplacementRequest(
                "sr-2-r", (long) d.version(), oldTruck, List.of(standby), D0.plusSeconds(600)));

        List<TruckLedgerEntry> ledger = trucks.ledger();
        TruckLedgerEntry oldEntry = ledger.stream().filter(e -> e.id() == oldTruck).findFirst().orElseThrow();
        TruckLedgerEntry standbyEntry = ledger.stream().filter(e -> e.id() == standby).findFirst()
                .orElseThrow();
        assertEquals("SUPPLY", oldEntry.occupationType());
        assertEquals(d.id(), oldEntry.dispatchId());
        assertNull(oldEntry.replacementId());
        assertEquals("STANDBY", standbyEntry.occupationType());
        assertNull(standbyEntry.dispatchId(), "热备预占不挂调度单");
        assertTrue(standbyEntry.replacementId() > 0);
    }

    @Test
    void 部分到场撤回释放全部新车并保留旧车与旧证据() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck("sr-3-old", 100, "F01");
        long n1 = registerTruck("sr-3-n1", 60, "F01");
        long n2 = registerTruck("sr-3-n2", 60, "F01");
        GeneratorDispatchView d = supplying(order, "sr-3", 90, List.of(oldTruck));
        ReplacementView r = replacements.create(d.id(), new CreateReplacementRequest(
                "sr-3-r", (long) d.version(), oldTruck, List.of(n1, n2), D0.plusSeconds(600)));
        replacements.arrive(r.id(), new ReplacementArrivalRequest(
                "sr-3-a1", 1L, n1, D0.plusSeconds(700)));

        ReplacementRevokeResponse revoked = replacements.revoke(r.id(),
                new ReplacementRevokeRequest("sr-3-revoke", 2L));
        assertEquals(List.of(n1, n2), revoked.releasedTruckIds());
        assertEquals(oldTruck, revoked.oldTruckId());
        assertEquals("REVOKED", revoked.status());
        assertEquals(3, revoked.version());

        assertEquals(0, count("active_occupation", "truck_id = ?", n1), "预占车全部释放");
        assertEquals(0, count("active_occupation", "truck_id = ?", n2));
        assertEquals("SUPPLY", occupationType(oldTruck), "旧车保留供电");
        assertEquals(1, count("supply_evidence", "dispatch_id = ?", d.id()), "旧供电证据不覆盖");
        // 撤回后不可再登记/确认/撤回
        assertThrows(BusinessException.class, () -> replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-3-a2", 3L, n2, D0.plusSeconds(800))));
        assertThrows(BusinessException.class, () -> replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest("sr-3-op", 3L, "操作员甲", D0.plusSeconds(900))));
        assertThrows(BusinessException.class, () -> replacements.revoke(r.id(),
                new ReplacementRevokeRequest("sr-3-revoke2", 3L)));

        // 释放出的新车可为其他工单调度
        long order2 = newOrder();
        GeneratorDispatchView d2 = dispatches.create(new CreateGeneratorDispatchRequest(
                "sr-3-d2", order2, 1L, List.of(new LoadItem("市一医院", 50)), List.of(n1),
                D0.plusSeconds(1000)));
        assertEquals("SUPPLY", jdbc.queryForObject(
                "SELECT occupation_type FROM active_occupation WHERE truck_id = ?", String.class, n1));
        assertEquals(d2.id(), occupationDispatch(n1));
    }

    @Test
    void 申请失败分支_状态版本馈线容量占用重复() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck("sr-4-old", 100, "F01");
        long other = registerTruck("sr-4-other", 100, "F01");
        long f02 = registerTruck("sr-4-f02", 100, "F02");
        long small1 = registerTruck("sr-4-s1", 60, "F01");
        long small2 = registerTruck("sr-4-s2", 30, "F01");
        GeneratorDispatchView d = dispatches.create(new CreateGeneratorDispatchRequest(
                "sr-4-c", order, 1L, List.of(new LoadItem("市一医院", 100)), List.of(oldTruck), D0));

        // 非SUPPLYING不可申请
        assertThrows(BusinessException.class, () -> replacements.create(d.id(),
                new CreateReplacementRequest("sr-4-e1", 1L, oldTruck, List.of(other), D0.plusSeconds(10))));
        dispatches.arrive(d.id(), new ArrivalRequest("sr-4-a", 1L, oldTruck, D0.plusSeconds(100)));
        final GeneratorDispatchView ds = dispatches.view(d.id());
        final long did = ds.id();

        // 调度单版本不符 409
        assertThrows(ConflictException.class, () -> replacements.create(did,
                new CreateReplacementRequest("sr-4-e2", 1L, oldTruck, List.of(other), D0.plusSeconds(20))),
                "旧版本应409");
        // 旧车不在组合内
        assertThrows(BusinessException.class, () -> replacements.create(did,
                new CreateReplacementRequest("sr-4-e3", (long) ds.version(), other, List.of(small1),
                        D0.plusSeconds(20))));
        // 异馈线
        assertThrows(BusinessException.class, () -> replacements.create(did,
                new CreateReplacementRequest("sr-4-e4", (long) ds.version(), oldTruck, List.of(f02),
                        D0.plusSeconds(20))));
        // 容量不足（60+30<100）整单拒绝且不留预占
        assertThrows(BusinessException.class, () -> replacements.create(did,
                new CreateReplacementRequest("sr-4-e5", (long) ds.version(), oldTruck,
                        List.of(small1, small2), D0.plusSeconds(20))));
        assertEquals(0, count("active_occupation", "truck_id = ?", small1));
        assertEquals(0, count("active_occupation", "truck_id = ?", small2));
        assertEquals(0, count("standby_replacement", "dispatch_id = ?", did));
        // 新车与旧车相同 / 列表重复
        assertThrows(IllegalArgumentException.class, () -> replacements.create(did,
                new CreateReplacementRequest("sr-4-e6", (long) ds.version(), oldTruck, List.of(oldTruck),
                        D0.plusSeconds(20))));
        assertThrows(IllegalArgumentException.class, () -> replacements.create(did,
                new CreateReplacementRequest("sr-4-e7", (long) ds.version(), oldTruck,
                        List.of(other, other), D0.plusSeconds(20))));
        // 接替单不存在 404
        assertThrows(NotFoundException.class, () -> replacements.view(99999L));
    }

    @Test
    void 每辆旧车最多一个未结束接替_新车被占用全单拒绝() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck("sr-5-old", 100, "F01");
        long n1 = registerTruck("sr-5-n1", 100, "F01");
        long n2 = registerTruck("sr-5-n2", 100, "F01");
        GeneratorDispatchView d = supplying(order, "sr-5", 100, List.of(oldTruck));
        replacements.create(d.id(), new CreateReplacementRequest(
                "sr-5-r1", (long) d.version(), oldTruck, List.of(n1), D0.plusSeconds(600)));

        assertThrows(BusinessException.class, () -> replacements.create(d.id(),
                new CreateReplacementRequest("sr-5-r2", (long) d.version(), oldTruck, List.of(n2),
                        D0.plusSeconds(700))), "同一旧车不得有两个未结束接替");
        // n1已被热备预占：其他方案引用n1应整单拒绝，n2不被连带占用
        assertThrows(BusinessException.class, () -> replacements.create(d.id(),
                new CreateReplacementRequest("sr-5-r3", (long) d.version(), oldTruck, List.of(n1, n2),
                        D0.plusSeconds(800))), "热备预占车不可再被预占");
        assertEquals(0, count("active_occupation", "truck_id = ?", n2), "整单拒绝不留预占");
        assertEquals(1, count("standby_replacement", "dispatch_id = ?", d.id()));
    }

    @Test
    void 接替申请幂等重放异参409失败不占键() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck("sr-6-old", 100, "F01");
        long n1 = registerTruck("sr-6-n1", 60, "F01");
        long n2 = registerTruck("sr-6-n2", 40, "F01");
        GeneratorDispatchView d = supplying(order, "sr-6", 100, List.of(oldTruck));

        // 先因容量不足失败（60<100）不占键，同键补足新车后成功
        assertThrows(BusinessException.class, () -> replacements.create(d.id(),
                new CreateReplacementRequest("sr-6-k", (long) d.version(), oldTruck, List.of(n1),
                        D0.plusSeconds(600))));
        assertEquals(0, count("gen_idempotency", "request_id = 'sr-6-k'"), "失败不占键");
        ReplacementView first = replacements.create(d.id(), new CreateReplacementRequest(
                "sr-6-k", (long) d.version(), oldTruck, List.of(n2, n1), D0.plusSeconds(600)));
        ReplacementView replay = replacements.create(d.id(), new CreateReplacementRequest(
                "sr-6-k", (long) d.version(), oldTruck, List.of(n1, n2), D0.plusSeconds(600)));
        assertEquals(first, replay, "同键同参重放首次冻结响应(车辆集合顺序无关)");
        assertEquals(1, count("standby_replacement", "dispatch_id = ?", d.id()));

        assertThrows(ConflictException.class, () -> replacements.create(d.id(),
                new CreateReplacementRequest("sr-6-k", (long) d.version(), oldTruck, List.of(n1),
                        D0.plusSeconds(601))), "同键异参(时刻不同)应409");
    }

    @Test
    void 到场与确认幂等且旧版本409_同人不可重复确认() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck("sr-7-old", 100, "F01");
        long n1 = registerTruck("sr-7-n1", 60, "F01");
        long n2 = registerTruck("sr-7-n2", 40, "F01");
        GeneratorDispatchView d = supplying(order, "sr-7", 100, List.of(oldTruck));
        ReplacementView r = replacements.create(d.id(), new CreateReplacementRequest(
                "sr-7-r", (long) d.version(), oldTruck, List.of(n1, n2), D0.plusSeconds(600)));

        ReplacementArrivalResponse a1 = replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-7-a1", 1L, n1, D0.plusSeconds(700)));
        ReplacementArrivalResponse a1Replay = replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-7-a1", 1L, n1, D0.plusSeconds(700)));
        assertEquals(a1, a1Replay, "到场同键同参重放");
        assertThrows(ConflictException.class, () -> replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-7-a1", 1L, n1, D0.plusSeconds(701))),
                "同键异参409");
        assertThrows(BusinessException.class, () -> replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-7-aX", 2L, oldTruck, D0.plusSeconds(702))),
                "方案外车辆不可登记");
        assertThrows(ConflictException.class, () -> replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-7-a2", 1L, n2, D0.plusSeconds(702))),
                "旧版本号应409");

        replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-7-a2", 2L, n2, D0.plusSeconds(702)));
        ReplacementSwitchResponse op1 = replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest("sr-7-op1", 3L, "操作员甲", D0.plusSeconds(800)));
        assertFalse(op1.switched());
        ReplacementSwitchResponse op1Replay = replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest("sr-7-op1", 3L, "操作员甲", D0.plusSeconds(800)));
        assertEquals(op1, op1Replay, "确认同键同参重放");
        assertThrows(ConflictException.class, () -> replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest("sr-7-opX", 4L, "操作员甲", D0.plusSeconds(801))),
                "同一操作员不可重复确认");
        assertThrows(ConflictException.class, () -> replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest("sr-7-op2", 3L, "操作员乙", D0.plusSeconds(801))),
                "旧版本号应409");
        assertEquals("WAITING", replacements.view(r.id()).status());
        assertEquals("SUPPLY", occupationType(oldTruck), "切换前旧车始终保留");
    }

    @Test
    void 旧超时改派不能移走热备预占车() {
        freezeAt(T0);
        long order = newOrder();
        long oldTruck = registerTruck("sr-8-old", 100, "F01");
        long standby = registerTruck("sr-8-standby", 100, "F01");
        GeneratorDispatchView d1 = supplying(order, "sr-8", 90, List.of(oldTruck));
        replacements.create(d1.id(), new CreateReplacementRequest(
                "sr-8-r", (long) d1.version(), oldTruck, List.of(standby), D0.plusSeconds(600)));

        // 另一工单的超时调度单试图改派到热备预占车，应被拒绝，预占不移走
        long order2 = newOrder();
        long t2 = registerTruck("sr-8-t2", 200, "F01");
        GeneratorDispatchView d2 = dispatches.create(new CreateGeneratorDispatchRequest(
                "sr-8-c2", order2, 1L, List.of(new LoadItem("市一医院", 100)), List.of(t2),
                D0.plusSeconds(60)));
        freezeAt(T0.plusMinutes(31));
        dispatches.evaluateTimeouts();
        int v2 = jdbc.queryForObject("SELECT version FROM generator_dispatch WHERE id = ?",
                Integer.class, d2.id());
        assertThrows(BusinessException.class, () -> dispatches.reassign(d2.id(),
                new ReassignRequest("sr-8-reassign", (long) v2, List.of(standby),
                        D0.plusSeconds(2000))), "热备预占车不可被超时改派占用");
        assertEquals("STANDBY", occupationType(standby), "热备预占保持不变");
        assertNull(occupationDispatch(standby), "预占仍不挂调度单");
        assertEquals("TIMED_OUT", jdbc.queryForObject(
                "SELECT status FROM generator_dispatch WHERE id = ?", String.class, d2.id()));
    }

    @Test
    void 正式复电关闭未切换接替并释放新旧车() {
        freezeAt(T0);
        long order = newOrder();
        teamDispatch.dispatch(new DispatchRequest("sr-9-d", order, teamId("抢修一班")));
        long oldTruck = registerTruck("sr-9-old", 100, "F01");
        long n1 = registerTruck("sr-9-n1", 60, "F01");
        long n2 = registerTruck("sr-9-n2", 40, "F01");
        GeneratorDispatchView d = dispatches.create(new CreateGeneratorDispatchRequest(
                "sr-9-c", order, (long) orderVersion(order), List.of(new LoadItem("市一医院", 100)),
                List.of(oldTruck), D0));
        dispatches.arrive(d.id(), new ArrivalRequest("sr-9-a", (long) d.version(), oldTruck,
                D0.plusSeconds(100)));
        d = dispatches.view(d.id());
        ReplacementView r = replacements.create(d.id(), new CreateReplacementRequest(
                "sr-9-r", (long) d.version(), oldTruck, List.of(n1, n2), D0.plusSeconds(600)));
        replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-9-ra", 1L, n1, D0.plusSeconds(700)));

        restore.confirm(new RestoreConfirmRequest("sr-9-c1", order, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("sr-9-c2", order, "FIELD", "现场员乙",
                new BigDecimal("100")));

        assertEquals("RESTORED", orderStatus(order));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM generator_dispatch WHERE id = ?", String.class, d.id()));
        assertEquals("CANCELLED", replacements.view(r.id()).status(), "未结束接替同事务关闭");
        assertEquals(0, count("active_occupation", "1 = 1"), "新旧车全部释放, 无孤立占用");
        assertEquals(1, count("supply_evidence", "dispatch_id = ?", d.id()), "供电证据保留");
    }

    @Test
    void 切换后复电关闭接替且新车释放无孤立占用() {
        freezeAt(T0);
        long order = newOrder();
        teamDispatch.dispatch(new DispatchRequest("sr-10-d", order, teamId("抢修一班")));
        long oldTruck = registerTruck("sr-10-old", 100, "F01");
        long n1 = registerTruck("sr-10-n1", 60, "F01");
        long n2 = registerTruck("sr-10-n2", 40, "F01");
        GeneratorDispatchView d = dispatches.create(new CreateGeneratorDispatchRequest(
                "sr-10-c", order, (long) orderVersion(order), List.of(new LoadItem("市一医院", 100)),
                List.of(oldTruck), D0));
        dispatches.arrive(d.id(), new ArrivalRequest("sr-10-a", (long) d.version(), oldTruck,
                D0.plusSeconds(100)));
        d = dispatches.view(d.id());
        ReplacementView r = replacements.create(d.id(), new CreateReplacementRequest(
                "sr-10-r", (long) d.version(), oldTruck, List.of(n1, n2), D0.plusSeconds(600)));
        replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-10-a1", 1L, n1, D0.plusSeconds(700)));
        replacements.arrive(r.id(),
                new ReplacementArrivalRequest("sr-10-a2", 2L, n2, D0.plusSeconds(710)));
        replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest("sr-10-op1", 3L, "操作员甲", D0.plusSeconds(800)));
        replacements.confirmSwitch(r.id(),
                new ReplacementSwitchRequest("sr-10-op2", 4L, "操作员乙", D0.plusSeconds(810)));
        assertEquals("SWITCHED", replacements.view(r.id()).status());

        restore.confirm(new RestoreConfirmRequest("sr-10-c1", order, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("sr-10-c2", order, "FIELD", "现场员乙",
                new BigDecimal("100")));

        assertEquals("CANCELLED", replacements.view(r.id()).status());
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM generator_dispatch WHERE id = ?", String.class, d.id()));
        assertEquals(0, count("active_occupation", "1 = 1"), "切换后复电也不得留孤立占用");
        assertEquals(1, count("replacement_switch_evidence", "replacement_id = ?", r.id()),
                "切换证据保留");
    }

    @Test
    void 工单合并关闭未结束接替() {
        freezeAt(T0);
        long orderA = newOrder();
        long orderB = newOrder();
        long oldTruck = registerTruck("sr-11-old", 100, "F01");
        long n1 = registerTruck("sr-11-n1", 100, "F01");
        GeneratorDispatchView d = supplying(orderA, "sr-11", 80, List.of(oldTruck));
        ReplacementView r = replacements.create(d.id(), new CreateReplacementRequest(
                "sr-11-r", (long) d.version(), oldTruck, List.of(n1), D0.plusSeconds(600)));

        workOrders.merge(new MergeRequest(List.of(orderA, orderB)));

        assertEquals("CANCELLED", replacements.view(r.id()).status());
        assertEquals(0, count("active_occupation", "1 = 1"), "合并后旧车与热备预占车全部释放");
    }
}
