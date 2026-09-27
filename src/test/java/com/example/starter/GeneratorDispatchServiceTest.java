package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.starter.service.FaultReportService;
import com.example.starter.service.GeneratorDispatchService;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.UnprocessableException;
import com.example.starter.web.dto.GeneratorDispatchRequest;
import com.example.starter.web.dto.GeneratorDispatchResponse;
import com.example.starter.web.dto.GeneratorOrderView;
import com.example.starter.web.dto.LoadItemRequest;
import com.example.starter.web.dto.VehicleArrivalRequest;
import com.example.starter.web.dto.VehicleArrivalResponse;
import com.example.starter.web.dto.VehicleRegisterRequest;
import com.example.starter.web.dto.VehicleView;

/**
 * 发电车组合调度主流程与失败分支：登记台账、恰等容量组合、逐车到场全到场供电、
 * 容量/占用/馈线拒绝、版本与同键异参 409、不存在 404、非法参数 400。
 */
class GeneratorDispatchServiceTest extends AbstractRepairTest {

    /** UTC 基准时刻；下发/截止/到场均使用 UTC。 */
    private static final LocalDateTime U0 = LocalDateTime.of(2026, 9, 26, 2, 0, 0);
    /** 对应的上海本地时间（UTC+8），用于接报与时钟固定。 */
    private static final LocalDateTime S0 = U0.plusHours(8);

    @Autowired
    private GeneratorDispatchService generator;

    @Autowired
    private FaultReportService reports;

    private long specialOrder(String requestId) {
        return reports.report(new com.example.starter.web.dto.FaultReportRequest(
                requestId, "F01", "故障点-" + requestId, 30, "市一医院", S0)).workOrderId();
    }

    private long vehicle(String reqId, String code, int kw, String feeder) {
        VehicleView v = generator.registerVehicle(
                new VehicleRegisterRequest(reqId, code, kw, feeder, true));
        return v.id();
    }

    private List<LoadItemRequest> hospitalLoad(int kw) {
        return List.of(new LoadItemRequest("市一医院", kw));
    }

    @Test
    void 登记发电车并入台账且幂等重放() {
        VehicleView first = generator.registerVehicle(
                new VehicleRegisterRequest("v-1", "发电车T1", 600, "F01", true));
        assertTrue(first.id() > 0);
        assertTrue(first.available());
        assertFalse(first.occupied());

        VehicleView replay = generator.registerVehicle(
                new VehicleRegisterRequest("v-1", "发电车T1", 600, "F01", true));
        assertEquals(first.id(), replay.id());

        // 同键异参 409
        assertThrows(ConflictException.class, () -> generator.registerVehicle(
                new VehicleRegisterRequest("v-1", "发电车T1", 601, "F01", true)));
        // 容量必须正整数
        assertThrows(IllegalArgumentException.class, () -> generator.registerVehicle(
                new VehicleRegisterRequest("v-bad", "发电车TB", 0, "F01", true)));
        // 编号重复 422
        generator.registerVehicle(new VehicleRegisterRequest("v-2", "重号车", 100, "F01", true));
        assertThrows(UnprocessableException.class, () -> generator.registerVehicle(
                new VehicleRegisterRequest("v-3", "重号车", 100, "F01", true)));

        List<VehicleView> f01 = generator.listVehicles("F01");
        assertTrue(f01.stream().anyMatch(v -> v.code().equals("发电车T1")));
        assertTrue(generator.listVehicles(null).size() >= f01.size());
    }

    @Test
    void 恰等容量组合下发成功并原子占用全部车辆() {
        freezeAt(S0);
        long order = specialOrder("gd-1");
        long g1 = vehicle("gd-v1", "车A", 500, "F01");
        long g2 = vehicle("gd-v2", "车B", 300, "F01");

        GeneratorDispatchResponse resp = generator.dispatch(new GeneratorDispatchRequest(
                "gd-d1", order, 1, List.of(g1, g2), hospitalLoad(800), U0));

        assertEquals("DISPATCHED", resp.status());
        assertEquals(1, resp.version());
        assertEquals(800, resp.totalLoadKw());
        assertEquals(800, resp.totalCapacityKw());
        assertEquals(U0.plusMinutes(30), resp.deadlineAt());
        assertFalse(resp.replayed());

        List<VehicleView> vehicles = generator.listVehicles("F01");
        for (VehicleView v : vehicles) {
            if (v.id() == g1 || v.id() == g2) {
                assertTrue(v.occupied(), "组合内车辆应被占用");
                assertEquals(resp.orderId(), v.occupiedOrderId());
            }
        }
        assertEquals(2, generator.occupationLedger(null).stream()
                .filter(o -> o.releasedAt() == null && o.orderId().equals(resp.orderId())).count());
    }

    @Test
    void 同键同参重放首次冻结结果而异参409且失败不占键() {
        freezeAt(S0);
        long order = specialOrder("gd-2");
        long g1 = vehicle("gd-v3", "车C", 500, "F01");
        long g2 = vehicle("gd-v4", "车D", 300, "F01");

        GeneratorDispatchRequest req = new GeneratorDispatchRequest(
                "gd-d2", order, 1, List.of(g1, g2), hospitalLoad(800), U0);
        GeneratorDispatchResponse first = generator.dispatch(req);
        GeneratorDispatchResponse replay = generator.dispatch(req);
        assertTrue(replay.replayed());
        assertEquals(first.orderId(), replay.orderId());

        // 异参（时刻不同）409
        assertThrows(ConflictException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-d2", order, 1, List.of(g1, g2), hospitalLoad(800), U0.plusSeconds(1))));
        // 异参（版本不同）409
        assertThrows(ConflictException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-d2", order, 2, List.of(g1, g2), hospitalLoad(800), U0)));

        // 一次失败的新键（容量不足）不得占用幂等键；在另一张新单（版本仍为1）上修正参数后同键可成功
        long fresh = specialOrder("gd-2b");
        long gSmall = vehicle("gd-v5", "车E", 300, "F01");
        assertThrows(UnprocessableException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-d3", fresh, 1, List.of(gSmall), hospitalLoad(800), U0)));
        GeneratorDispatchResponse fixed = generator.dispatch(new GeneratorDispatchRequest(
                "gd-d3", fresh, 1, List.of(
                        vehicle("gd-v5a", "车E1", 500, "F01"),
                        vehicle("gd-v5b", "车E2", 300, "F01")),
                hospitalLoad(800), U0.plusMinutes(2)));
        assertFalse(fixed.replayed());
    }

    @Test
    void 容量不足_车辆冲突_馈线不符_版本错误分别拒绝且整单不派() {
        freezeAt(S0);
        long order = specialOrder("gd-3");
        long g1 = vehicle("gd-v6", "车F", 500, "F01");
        long g2 = vehicle("gd-v7", "车G", 300, "F01");
        long gF02 = vehicle("gd-v8", "车H", 900, "F02");

        // 总容量 500 < 负荷 800
        assertThrows(UnprocessableException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-f1", order, 1, List.of(g1), hospitalLoad(800), U0)));

        // 先成功占用 g1+g2
        generator.dispatch(new GeneratorDispatchRequest(
                "gd-f2", order, 1, List.of(g1, g2), hospitalLoad(800), U0));
        long other = specialOrder("gd-4");
        long g3 = vehicle("gd-v9", "车I", 800, "F01");
        // g1 已被占用：与空闲 g3 组合仍整单不派
        assertThrows(UnprocessableException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-f3", other, 1, List.of(g1, g3), hospitalLoad(800), U0.plusMinutes(5))));
        // 被拒整单不产生任何占用（g3 仍空闲）
        assertFalse(generator.listVehicles("F01").stream()
                .filter(v -> v.id().equals(g3)).findFirst().orElseThrow().occupied());
        assertEquals(0, count("generator_dispatch_order", "work_order_id = ?", other));

        // 馈线不符
        assertThrows(UnprocessableException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-f4", other, 1, List.of(gF02), hospitalLoad(800), U0.plusMinutes(6))));

        // 版本错误 409
        assertThrows(ConflictException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-f5", other, 9, List.of(g3), hospitalLoad(800), U0.plusMinutes(7))));

        // 不存在的工单 404、不存在的车 404
        assertThrows(NotFoundException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-f6", 999999L, 1, List.of(g3), hospitalLoad(800), U0.plusMinutes(8))));
        assertThrows(NotFoundException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-f7", other, 1, List.of(999999L), hospitalLoad(800), U0.plusMinutes(9))));

        // 集合重复/为空 400
        assertThrows(IllegalArgumentException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-f8", other, 1, List.of(g3, g3), hospitalLoad(800), U0.plusMinutes(10))));
        assertThrows(IllegalArgumentException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-f9", other, 1, List.of(g3), List.of(), U0.plusMinutes(11))));
        // 负荷用户不属于该馈线
        assertThrows(UnprocessableException.class, () -> generator.dispatch(new GeneratorDispatchRequest(
                "gd-f10", other, 1, List.of(g3),
                List.of(new LoadItemRequest("数据中心A", 100)), U0.plusMinutes(12))));
    }

    @Test
    void 逐车到场部分不算恢复全部到场才供电且重复到场拒绝() {
        freezeAt(S0);
        long order = specialOrder("gd-5");
        long g1 = vehicle("gd-v10", "车J", 500, "F01");
        long g2 = vehicle("gd-v11", "车K", 300, "F01");
        GeneratorDispatchResponse d = generator.dispatch(new GeneratorDispatchRequest(
                "gd-d4", order, 1, List.of(g1, g2), hospitalLoad(800), U0));

        VehicleArrivalResponse a1 = generator.arrive(new VehicleArrivalRequest(
                "gd-a1", d.orderId(), 1, g1, U0.plusMinutes(10)));
        assertEquals("PARTIAL_ARRIVAL", a1.status());
        assertEquals(2, a1.version());
        assertEquals(1, a1.arrivedCount());
        assertFalse(a1.supplied(), "部分到场不算全负荷恢复");

        // 同一车重复到场 422（到场唯一）
        assertThrows(UnprocessableException.class, () -> generator.arrive(new VehicleArrivalRequest(
                "gd-a2", d.orderId(), 2, g1, U0.plusMinutes(11))));
        // 非本单车 422
        long other = vehicle("gd-v12", "车L", 300, "F01");
        assertThrows(UnprocessableException.class, () -> generator.arrive(new VehicleArrivalRequest(
                "gd-a3", d.orderId(), 2, other, U0.plusMinutes(12))));
        // 版本错误 409
        assertThrows(ConflictException.class, () -> generator.arrive(new VehicleArrivalRequest(
                "gd-a4", d.orderId(), 1, g2, U0.plusMinutes(13))));

        VehicleArrivalResponse a2 = generator.arrive(new VehicleArrivalRequest(
                "gd-a5", d.orderId(), 2, g2, U0.plusMinutes(20)));
        assertEquals("POWER_SUPPLIED", a2.status());
        assertTrue(a2.supplied());
        assertEquals(U0.plusMinutes(20), a2.suppliedAt());

        // 供电证据保留
        assertEquals(1, count("generator_supply_record", "order_id = ?", d.orderId()));
        GeneratorOrderView view = generator.viewOrder(d.orderId());
        assertEquals("POWER_SUPPLIED", view.status());
        assertEquals(2, view.vehicles().stream().filter(v -> v.arrivedAt() != null).count());
        assertTrue(view.events().stream().anyMatch(e -> e.startsWith("ALL_ARRIVED_SUPPLIED")));
    }

    @Test
    void 到场幂等重放首次结果() {
        freezeAt(S0);
        long order = specialOrder("gd-6");
        long g1 = vehicle("gd-v13", "车M", 800, "F01");
        GeneratorDispatchResponse d = generator.dispatch(new GeneratorDispatchRequest(
                "gd-d5", order, 1, List.of(g1), hospitalLoad(800), U0));
        VehicleArrivalRequest req = new VehicleArrivalRequest(
                "gd-a6", d.orderId(), 1, g1, U0.plusMinutes(5));
        VehicleArrivalResponse first = generator.arrive(req);
        assertTrue(first.supplied());
        VehicleArrivalResponse replay = generator.arrive(req);
        assertTrue(replay.replayed());
        assertEquals(first.status(), replay.status());
        // 同键异参 409
        assertThrows(ConflictException.class, () -> generator.arrive(new VehicleArrivalRequest(
                "gd-a6", d.orderId(), 1, g1, U0.plusMinutes(6))));
    }
}
