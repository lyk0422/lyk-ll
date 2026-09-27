package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.DispatchService;
import com.example.starter.service.FaultReportService;
import com.example.starter.service.GeneratorDispatchService;
import com.example.starter.service.RestoreService;
import com.example.starter.service.WorkOrderService;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.GeneratorDispatchRequest;
import com.example.starter.web.dto.GeneratorDispatchResponse;
import com.example.starter.web.dto.GeneratorOrderView;
import com.example.starter.web.dto.GeneratorReassignRequest;
import com.example.starter.web.dto.GeneratorReassignResponse;
import com.example.starter.web.dto.LoadItemRequest;
import com.example.starter.web.dto.MergeRequest;
import com.example.starter.web.dto.OccupationEntry;
import com.example.starter.web.dto.RestoreConfirmRequest;
import com.example.starter.web.dto.VehicleArrivalRequest;
import com.example.starter.web.dto.VehicleArrivalResponse;
import com.example.starter.web.dto.VehicleRegisterRequest;
import com.example.starter.web.dto.VehicleView;

/**
 * 到达超时（恰满30分钟）、迟到只记事件、整体改派原子切换、工单合并/正式复电原子撤销并保留供电证据。
 */
class GeneratorLifecycleTest extends AbstractRepairTest {

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
    @Autowired
    private WorkOrderService workOrders;

    private long specialOrder(String requestId) {
        return reports.report(new FaultReportRequest(
                requestId, "F01", "故障点-" + requestId, 30, "市一医院", S0)).workOrderId();
    }

    private long vehicle(String reqId, String code, int kw, String feeder) {
        return generator.registerVehicle(
                new VehicleRegisterRequest(reqId, code, kw, feeder, true)).id();
    }

    private GeneratorDispatchResponse dispatchTwo(String reqId, long order, long g1, long g2,
            LocalDateTime at, int load) {
        return generator.dispatch(new GeneratorDispatchRequest(
                reqId, order, 1, List.of(g1, g2), List.of(new LoadItemRequest("市一医院", load)), at));
    }

    @Test
    void 恰满30分钟未全到场转超时且迟到到场只记事件不占车() {
        freezeAt(S0);
        long order = specialOrder("gl-1");
        long g1 = vehicle("gl-v1", "超时车A", 500, "F01");
        long g2 = vehicle("gl-v2", "超时车B", 300, "F01");
        GeneratorDispatchResponse d = dispatchTwo("gl-d1", order, g1, g2, U0, 800);

        // 第10分钟 g1 到场
        generator.arrive(new VehicleArrivalRequest("gl-a1", d.orderId(), 1, g1, U0.plusMinutes(10)));

        // 29:59 仍未超时
        freezeAt(S0.plusMinutes(29).plusSeconds(59));
        assertEquals("PARTIAL_ARRIVAL", generator.viewOrder(d.orderId()).status());

        // 恰满 30 分钟：查询即转超时
        freezeAt(S0.plusMinutes(30));
        assertEquals("ARRIVAL_TIMEOUT", generator.viewOrder(d.orderId()).status());

        // 超时时刻 g2 才到场：只记 LATE_ARRIVAL，不回写到场、不改变车占用
        VehicleArrivalResponse late = generator.arrive(
                new VehicleArrivalRequest("gl-a2", d.orderId(), 3, g2, U0.plusMinutes(30)));
        assertTrue(late.late());
        assertFalse(late.supplied());
        assertEquals("ARRIVAL_TIMEOUT", late.status());
        GeneratorOrderView view = generator.viewOrder(d.orderId());
        assertTrue(view.events().stream().anyMatch(e -> e.startsWith("LATE_ARRIVAL")));
        assertTrue(view.vehicles().stream().filter(v -> v.vehicleId().equals(g2))
                .findFirst().orElseThrow().arrivedAt() == null, "迟到车不得回写到场");
        // g2 仍处于旧单占用中（等待改派统一释放），台账可查
        assertTrue(generator.listVehicles("F01").stream().filter(v -> v.id().equals(g2))
                .findFirst().orElseThrow().occupied());
        assertEquals(0, count("generator_supply_record", "order_id = ?", d.orderId()));
    }

    @Test
    void 整体改派保留到场车释放其余并换新车且原子切换() {
        freezeAt(S0);
        long order = specialOrder("gl-2");
        long g1 = vehicle("gl-v3", "改派车A", 500, "F01");
        long g2 = vehicle("gl-v4", "改派车B", 300, "F01");
        long n1 = vehicle("gl-v5", "新车N1", 400, "F01");
        GeneratorDispatchResponse d = dispatchTwo("gl-d2", order, g1, g2, U0, 800);
        generator.arrive(new VehicleArrivalRequest("gl-a3", d.orderId(), 1, g1, U0.plusMinutes(10)));
        freezeAt(S0.plusMinutes(31));

        GeneratorReassignResponse r = generator.reassign(new GeneratorReassignRequest(
                "gl-r1", d.orderId(), 3, List.of(n1), U0.plusMinutes(31)));

        assertEquals(d.orderId(), r.oldOrderId());
        assertTrue(r.newOrderId() > 0);
        assertEquals(List.of(g1), r.carriedOverVehicleIds(), "已到场车保留");
        assertEquals(List.of(n1), r.newVehicleIds());
        assertEquals(1, r.reassignCount());

        // 旧单终态
        GeneratorOrderView oldView = generator.viewOrder(d.orderId());
        assertEquals("REDISPATCHED", oldView.status());
        assertEquals(r.newOrderId(), oldView.replacedById());
        // 新单：g1 结转并标记 carried_over 且保留到场时间；n1 未到场
        GeneratorOrderView newView = generator.viewOrder(r.newOrderId());
        assertEquals("PARTIAL_ARRIVAL", newView.status());
        assertEquals(800, newView.totalLoadKw());
        assertEquals(900, newView.totalCapacityKw());
        assertEquals(1, newView.vehicles().stream().filter(
                v -> v.vehicleId().equals(g1) && v.carriedOver() && v.arrivedAt() != null).count());
        assertEquals(1, newView.vehicles().stream().filter(
                v -> v.vehicleId().equals(n1) && !v.carriedOver() && v.arrivedAt() == null).count());

        // 占用台账：g2 已释放，g1 旧占用结转关闭且新占用打开，n1 占用
        List<OccupationEntry> ledger = generator.occupationLedger(null);
        OccupationEntry g2Occ = ledger.stream().filter(o -> o.vehicleId().equals(g2)
                && o.orderId().equals(d.orderId())).findFirst().orElseThrow();
        assertEquals("REDISPATCH_RELEASED", g2Occ.releaseReason());
        assertTrue(g2Occ.releasedAt() != null);
        OccupationEntry g1Old = ledger.stream().filter(o -> o.vehicleId().equals(g1)
                && o.orderId().equals(d.orderId())).findFirst().orElseThrow();
        assertEquals("REDISPATCH_CARRIED", g1Old.releaseReason());
        assertTrue(ledger.stream().anyMatch(o -> o.vehicleId().equals(g1)
                && o.orderId().equals(r.newOrderId()) && o.releasedAt() == null));
        assertTrue(ledger.stream().anyMatch(o -> o.vehicleId().equals(n1)
                && o.orderId().equals(r.newOrderId()) && o.releasedAt() == null));
        // g2 释放后空闲
        assertFalse(generator.listVehicles("F01").stream().filter(v -> v.id().equals(g2))
                .findFirst().orElseThrow().occupied());

        // 旧单不可再次改派
        assertThrows(UnprocessableException.class, () -> generator.reassign(new GeneratorReassignRequest(
                "gl-r2", d.orderId(), 4, List.of(n1), U0.plusMinutes(32))));

        // 旧单终止后被释放车 g2 迟到到场：只记事件，不再次占车、不回写到场
        VehicleArrivalResponse oldLate = generator.arrive(new VehicleArrivalRequest(
                "gl-a-old", d.orderId(), 4, g2, U0.plusMinutes(40)));
        assertTrue(oldLate.late());
        assertFalse(generator.listVehicles("F01").stream().filter(v -> v.id().equals(g2))
                .findFirst().orElseThrow().occupied());
        assertTrue(generator.viewOrder(d.orderId()).events().stream()
                .anyMatch(e -> e.startsWith("LATE_ARRIVAL")));
    }

    @Test
    void 改派后新车全部到场才供电且改派幂等重放() {
        freezeAt(S0);
        long order = specialOrder("gl-3");
        long g1 = vehicle("gl-v6", "链车A", 500, "F01");
        long g2 = vehicle("gl-v7", "链车B", 300, "F01");
        long n1 = vehicle("gl-v8", "链车N", 400, "F01");
        GeneratorDispatchResponse d = dispatchTwo("gl-d3", order, g1, g2, U0, 800);
        generator.arrive(new VehicleArrivalRequest("gl-a4", d.orderId(), 1, g1, U0.plusMinutes(5)));
        freezeAt(S0.plusMinutes(30));

        GeneratorReassignRequest req = new GeneratorReassignRequest(
                "gl-r3", d.orderId(), 3, List.of(n1), U0.plusMinutes(30));
        GeneratorReassignResponse first = generator.reassign(req);
        GeneratorReassignResponse replay = generator.reassign(req);
        assertTrue(replay.replayed());
        assertEquals(first.newOrderId(), replay.newOrderId());
        // 异参 409
        assertThrows(com.example.starter.web.ConflictException.class,
                () -> generator.reassign(new GeneratorReassignRequest(
                        "gl-r3", d.orderId(), 3, List.of(n1), U0.plusMinutes(31))));

        // 换入车到场后全到场供电
        VehicleArrivalResponse supplied = generator.arrive(new VehicleArrivalRequest(
                "gl-a5", first.newOrderId(), 1, n1, U0.plusMinutes(45)));
        assertTrue(supplied.supplied());
        assertEquals("POWER_SUPPLIED", supplied.status());
        assertEquals(2, supplied.arrivedCount());
        assertEquals(1, count("generator_supply_record", "order_id = ?", first.newOrderId()));
    }

    @Test
    void 改派容量不足或含旧车被拒绝且全方案不切换() {
        freezeAt(S0);
        long order = specialOrder("gl-4");
        long g1 = vehicle("gl-v9", "拒车A", 500, "F01");
        long g2 = vehicle("gl-v10", "拒车B", 300, "F01");
        long small = vehicle("gl-v11", "小车", 200, "F01");
        GeneratorDispatchResponse d = dispatchTwo("gl-d4", order, g1, g2, U0, 800);
        generator.arrive(new VehicleArrivalRequest("gl-a6", d.orderId(), 1, g1, U0.plusMinutes(5)));
        freezeAt(S0.plusMinutes(30));

        // g1 已到场保留贡献500，新车200 => 700 < 800
        assertThrows(UnprocessableException.class, () -> generator.reassign(new GeneratorReassignRequest(
                "gl-r4", d.orderId(), 3, List.of(small), U0.plusMinutes(30))));
        // 换入车不得为旧方案车
        assertThrows(UnprocessableException.class, () -> generator.reassign(new GeneratorReassignRequest(
                "gl-r5", d.orderId(), 3, List.of(g2), U0.plusMinutes(30))));
        // 全部回滚：旧单仍 ARRIVAL_TIMEOUT，无新单
        assertEquals("ARRIVAL_TIMEOUT", generator.viewOrder(d.orderId()).status());
        assertEquals(1, count("generator_dispatch_order", "work_order_id = ?", order));
    }

    @Test
    void 正式复电原子撤销未结束与供电中调度并释放车辆但保留证据() {
        freezeAt(S0);
        long order = specialOrder("gl-5");
        teamDispatch.dispatch(new DispatchRequest("gl-t1", order, teamId("抢修一班")));
        long g1 = vehicle("gl-v12", "复电车A", 500, "F01");
        long g2 = vehicle("gl-v13", "复电车B", 300, "F01");
        GeneratorDispatchResponse d = dispatchTwo("gl-d5", order, g1, g2, U0, 800);
        // 全部到场 -> 临时供电（车辆仍占用）
        generator.arrive(new VehicleArrivalRequest("gl-a7", d.orderId(), 1, g1, U0.plusMinutes(3)));
        generator.arrive(new VehicleArrivalRequest("gl-a8", d.orderId(), 2, g2, U0.plusMinutes(4)));
        assertEquals("POWER_SUPPLIED", generator.viewOrder(d.orderId()).status());

        // 调度+现场双确认正式复电
        restore.confirm(new RestoreConfirmRequest("gl-c1", order, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("gl-c2", order, "FIELD", "现场员乙",
                new BigDecimal("800")));

        // 临时调度被撤销，车辆释放，但供电证据保留
        assertEquals("CANCELLED", generator.viewOrder(d.orderId()).status());
        assertEquals("FORMAL_RESTORE", generator.viewOrder(d.orderId()).cancelReason());
        assertEquals(1, count("generator_supply_record", "order_id = ?", d.orderId()));
        for (VehicleView v : generator.listVehicles("F01")) {
            if (v.id() == g1 || v.id() == g2) {
                assertFalse(v.occupied(), "正式复电后发电车应释放");
            }
        }
        assertTrue(generator.occupationLedger(null).stream()
                .filter(o -> o.orderId().equals(d.orderId()))
                .allMatch(o -> "FORMAL_RESTORE".equals(o.releaseReason())));
    }

    @Test
    void 工单合并原子撤销源单调度并释放车辆保留证据() {
        freezeAt(S0);
        long a = specialOrder("gl-6");
        long b = specialOrder("gl-7");
        long g1 = vehicle("gl-v14", "合并车A", 500, "F01");
        long g2 = vehicle("gl-v15", "合并车B", 300, "F01");
        GeneratorDispatchResponse d = dispatchTwo("gl-d6", a, g1, g2, U0, 800);
        generator.arrive(new VehicleArrivalRequest("gl-a9", d.orderId(), 1, g1, U0.plusMinutes(2)));

        long merged = workOrders.merge(new MergeRequest(List.of(a, b))).newOrderId();
        assertEquals("MERGED", workOrders.view(a).status());
        assertEquals("CANCELLED", generator.viewOrder(d.orderId()).status());
        assertEquals("MERGED", generator.viewOrder(d.orderId()).cancelReason());
        for (VehicleView v : generator.listVehicles("F01")) {
            if (v.id() == g1 || v.id() == g2) {
                assertFalse(v.occupied());
            }
        }
        assertTrue(workOrders.view(merged).events().stream()
                .anyMatch(e -> e.startsWith("CREATED_BY_MERGE")));
    }
}
