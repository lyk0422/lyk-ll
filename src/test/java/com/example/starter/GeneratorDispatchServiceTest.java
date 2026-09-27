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
import com.example.starter.service.WorkOrderService;
import com.example.starter.support.AppClock;
import com.example.starter.web.BusinessException;
import com.example.starter.web.ConflictException;
import com.example.starter.web.NotFoundException;
import com.example.starter.web.dto.ArrivalRequest;
import com.example.starter.web.dto.ArrivalResponse;
import com.example.starter.web.dto.CreateGeneratorDispatchRequest;
import com.example.starter.web.dto.DispatchRequest;
import com.example.starter.web.dto.FaultReportRequest;
import com.example.starter.web.dto.GeneratorDispatchView;
import com.example.starter.web.dto.LoadItem;
import com.example.starter.web.dto.MergeRequest;
import com.example.starter.web.dto.ReassignRequest;
import com.example.starter.web.dto.ReassignResponse;
import com.example.starter.web.dto.RegisterTruckRequest;
import com.example.starter.web.dto.RestoreConfirmRequest;

/**
 * 发电车组合调度：冻结负荷与工单版本、容量覆盖（恰等合法）、一车一占用整单不派、
 * 到场逐车唯一且全部确认才供电、30分钟恰满超时、整体改派原子切换、复电/合并撤销释放车辆。
 */
class GeneratorDispatchServiceTest extends AbstractRepairTest {

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

    private int seq;

    private long newOrder() {
        seq++;
        return reports.report(new FaultReportRequest(
                "gd-r-" + seq, "F01", "故障点#" + seq, 30, "市一医院", T0)).workOrderId();
    }

    private int orderVersion(long orderId) {
        return jdbc.queryForObject("SELECT version FROM work_order WHERE id = ?", Integer.class, orderId);
    }

    private long seedTruck(int capacityKw, String feederId) {
        return jdbc.queryForObject("SELECT id FROM generator_truck WHERE capacity_kw = ? AND feeder_id = ?",
                Long.class, capacityKw, feederId);
    }

    private String dispatchStatus(long dispatchId) {
        return jdbc.queryForObject("SELECT status FROM generator_dispatch WHERE id = ?",
                String.class, dispatchId);
    }

    @Test
    void 组合多车调度原子冻结负荷与工单版本并占用全车() {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        long t2 = seedTruck(300, "F01");

        GeneratorDispatchView view = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-1", order, 1L,
                List.of(new LoadItem("市一医院", 280), new LoadItem("地铁一号线", 170)),
                List.of(t1, t2), D0));

        assertEquals(1, view.version());
        assertEquals("IN_PROGRESS", view.status());
        assertEquals(450, view.totalLoadKw());
        assertEquals(500, view.totalCapacityKw());
        assertEquals(1, view.orderVersion(), "应冻结工单版本1");
        assertEquals(2, view.loads().size());
        assertEquals(2, view.trucks().size());
        assertNull(view.suppliedAt());
        assertEquals(2, count("active_occupation", "dispatch_id = ?", view.id()));
        assertEquals(orderVersion(order), view.orderVersion());
    }

    @Test
    void 车总容量恰等合计负荷合法() {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        long t2 = seedTruck(300, "F01");
        GeneratorDispatchView view = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-2", order, 1L, List.of(new LoadItem("市一医院", 500)), List.of(t1, t2), D0));
        assertEquals(500, view.totalCapacityKw());
        assertEquals("IN_PROGRESS", view.status());
    }

    @Test
    void 容量不足整单不派且不留占用() {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> dispatches.create(new CreateGeneratorDispatchRequest(
                        "gd-3", order, 1L, List.of(new LoadItem("市一医院", 500)), List.of(t1), D0)));
        assertTrue(ex.getMessage().contains("不足覆盖"));
        assertEquals(0, count("generator_dispatch", "work_order_id = ?", order));
        assertEquals(0, count("active_occupation", "truck_id = ?", t1));
        assertEquals(0, count("gen_idempotency", "request_id = 'gd-3'"), "失败不占键");
    }

    @Test
    void 任一车冲突整单不派且其余车不占用() {
        freezeAt(T0);
        long orderA = newOrder();
        long orderB = newOrder();
        long t1 = seedTruck(200, "F01");
        long t2 = seedTruck(300, "F01");
        dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-4a", orderA, 1L, List.of(new LoadItem("市一医院", 150)), List.of(t1), D0));

        assertThrows(BusinessException.class, () -> dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-4b", orderB, 1L, List.of(new LoadItem("市一医院", 400)), List.of(t1, t2), D0)),
                "t1已占用应整单不派");
        assertEquals(0, count("generator_dispatch", "work_order_id = ?", orderB));
        assertEquals(0, count("active_occupation", "truck_id = ?", t2), "冲突时其余车不得被占用");
    }

    @Test
    void 停用车辆不可派() {
        freezeAt(T0);
        long order = newOrder();
        var truck = trucks.register(new RegisterTruckRequest("gd-5-t", 500, "F01"));
        trucks.updateAvailability(truck.id(),
                new com.example.starter.web.dto.UpdateTruckAvailabilityRequest("gd-5-u", 1L, false));
        assertThrows(BusinessException.class, () -> dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-5", order, 1L, List.of(new LoadItem("市一医院", 100)), List.of(truck.id()), D0)));
    }

    @Test
    void 工单不存在404已复电422版本不符409() {
        freezeAt(T0);
        long t1 = seedTruck(200, "F01");
        assertThrows(NotFoundException.class, () -> dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-6a", 99999L, 1L, List.of(new LoadItem("市一医院", 100)), List.of(t1), D0)));

        long order = newOrder();
        assertThrows(ConflictException.class, () -> dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-6b", order, 99L, List.of(new LoadItem("市一医院", 100)), List.of(t1), D0)),
                "工单版本不符应409");

        dispatch.dispatch(new DispatchRequest("gd-6-d", order, teamId("抢修一班")));
        restore.confirm(new RestoreConfirmRequest("gd-6-c1", order, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("gd-6-c2", order, "FIELD", "现场员乙",
                new BigDecimal("200")));
        assertThrows(BusinessException.class, () -> dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-6c", order, (long) orderVersion(order), List.of(new LoadItem("市一医院", 100)),
                List.of(t1), D0)), "已复电工单不可调度");
    }

    @Test
    void 调度幂等重放异参409且失败不占键() {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        long t2 = seedTruck(300, "F01");

        // 失败请求不占键：先因容量不足失败，同键修正参数后成功
        assertThrows(BusinessException.class, () -> dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-7", order, 1L, List.of(new LoadItem("市一医院", 500)), List.of(t1), D0)));
        GeneratorDispatchView first = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-7", order, 1L, List.of(new LoadItem("市一医院", 500)), List.of(t1, t2), D0));

        GeneratorDispatchView replay = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-7", order, 1L, List.of(new LoadItem("市一医院", 500)), List.of(t1, t2), D0));
        assertEquals(first, replay, "同键同参应重放首次冻结结果");
        assertEquals(1, count("generator_dispatch", "work_order_id = ?", order));

        assertThrows(ConflictException.class, () -> dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-7", order, 1L, List.of(new LoadItem("市一医院", 400)), List.of(t1, t2), D0)),
                "同键异参应409");
    }

    @Test
    void 含分隔符负荷文本的指纹仍可区分() {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-8", order, 1L, List.of(new LoadItem("医院|东区", 100)), List.of(t1), D0));
        // 同键但负荷清单结构不同（含分隔符拆分差异）应判为异参409
        assertThrows(ConflictException.class, () -> dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-8", order, 1L, List.of(new LoadItem("医院", 100), new LoadItem("|东区", 100)),
                List.of(t1), D0)));
    }

    @Test
    void 到场逐车唯一且全部确认才供电() {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        long t2 = seedTruck(300, "F01");
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-9", order, 1L, List.of(new LoadItem("市一医院", 400)), List.of(t1, t2), D0));

        ArrivalResponse a1 = dispatches.arrive(created.id(),
                new ArrivalRequest("gd-9-a1", 1L, t1, D0.plusSeconds(600)));
        assertEquals("ARRIVED", a1.outcome());
        assertEquals("IN_PROGRESS", a1.status());
        assertEquals(2, a1.version());
        assertEquals(0, count("supply_evidence", "dispatch_id = ?", created.id()),
                "部分到场不得算全负荷恢复");

        assertThrows(BusinessException.class, () -> dispatches.arrive(created.id(),
                new ArrivalRequest("gd-9-a2", 2L, t1, D0.plusSeconds(610))), "重复到场应拒绝");
        assertThrows(BusinessException.class, () -> dispatches.arrive(created.id(),
                new ArrivalRequest("gd-9-a3", 2L, seedTruck(150, "F02"), D0.plusSeconds(610))),
                "方案外车辆应拒绝");

        ArrivalResponse a2 = dispatches.arrive(created.id(),
                new ArrivalRequest("gd-9-a4", 2L, t2, D0.plusSeconds(900)));
        assertEquals("ALL_ARRIVED", a2.outcome());
        assertEquals("SUPPLYING", a2.status());
        assertEquals(3, a2.version());
        assertEquals(1, count("supply_evidence", "dispatch_id = ? AND total_load_kw = 400", created.id()));

        GeneratorDispatchView view = dispatches.view(created.id());
        assertEquals("SUPPLYING", view.status());
        assertTrue(view.trucks().stream().allMatch(t -> t.arrivedAt() != null));
        assertEquals(T0.plusSeconds(900), view.suppliedAt());
    }

    @Test
    void 下发满30分钟未全到场恰满转超时() {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-10", order, 1L, List.of(new LoadItem("市一医院", 100)), List.of(t1), D0));

        freezeAt(T0.plusMinutes(29).plusSeconds(59));
        assertTrue(dispatches.evaluateTimeouts().isEmpty(), "未满30分钟不应超时");

        freezeAt(T0.plusMinutes(30));
        assertEquals(List.of(created.id()), dispatches.evaluateTimeouts(), "恰满30分钟应触发超时");
        assertEquals("TIMED_OUT", dispatchStatus(created.id()));
        assertTrue(dispatches.evaluateTimeouts().isEmpty(), "重复评估不重复转换");
    }

    @Test
    void 超时后迟到到场只记事件不占车() {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-11", order, 1L, List.of(new LoadItem("市一医院", 100)), List.of(t1), D0));
        freezeAt(T0.plusMinutes(30));
        dispatches.evaluateTimeouts();
        int versionAfterTimeout = jdbc.queryForObject(
                "SELECT version FROM generator_dispatch WHERE id = ?", Integer.class, created.id());

        ArrivalResponse late = dispatches.arrive(created.id(),
                new ArrivalRequest("gd-11-a", (long) versionAfterTimeout, t1, D0.plusSeconds(1900)));
        assertEquals("LATE", late.outcome());
        assertEquals("TIMED_OUT", late.status());
        assertEquals(1, count("late_report_event",
                "work_order_id = ? AND event_type = 'LATE_TRUCK_ARRIVAL'", order));
        assertEquals(versionAfterTimeout, jdbc.queryForObject(
                "SELECT version FROM generator_dispatch WHERE id = ?", Integer.class, created.id()),
                "迟到事件不变更调度单版本");
        assertNull(jdbc.queryForObject(
                "SELECT arrived_at FROM dispatch_truck WHERE dispatch_id = ? AND truck_id = ?",
                java.sql.Timestamp.class, created.id(), t1), "迟到到场不记录到场时刻");
    }

    @Test
    void 超时整体改派原子切换保留到场车() {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        long t2 = seedTruck(300, "F01");
        long t4 = trucks.register(new RegisterTruckRequest("gd-12-t", 400, "F01")).id();
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-12", order, 1L, List.of(new LoadItem("市一医院", 450)), List.of(t1, t2), D0));
        dispatches.arrive(created.id(), new ArrivalRequest("gd-12-a", 1L, t1, D0.plusSeconds(300)));

        freezeAt(T0.plusMinutes(30));
        dispatches.evaluateTimeouts();
        int v = jdbc.queryForObject("SELECT version FROM generator_dispatch WHERE id = ?",
                Integer.class, created.id());

        ReassignResponse re = dispatches.reassign(created.id(),
                new ReassignRequest("gd-12-r", (long) v, List.of(t4), D0.plusSeconds(2000)));
        assertEquals(List.of(t1), re.keptTruckIds(), "已到场车保留");
        assertEquals(List.of(t2), re.releasedTruckIds(), "未到场车释放");
        assertEquals(1, re.version());

        assertEquals("REASSIGNED", dispatchStatus(created.id()));
        assertEquals(re.newDispatchId(), jdbc.queryForObject(
                "SELECT superseded_by_id FROM generator_dispatch WHERE id = ?", Long.class, created.id()));
        assertEquals(0, count("active_occupation", "truck_id = ?", t2), "释放车占用解除");
        assertEquals(re.newDispatchId(), jdbc.queryForObject(
                "SELECT dispatch_id FROM active_occupation WHERE truck_id = ?", Long.class, t1),
                "保留车占用转移到新单");
        assertEquals(re.newDispatchId(), jdbc.queryForObject(
                "SELECT dispatch_id FROM active_occupation WHERE truck_id = ?", Long.class, t4));

        GeneratorDispatchView newView = dispatches.view(re.newDispatchId());
        assertEquals("IN_PROGRESS", newView.status());
        assertEquals(450, newView.totalLoadKw(), "新单沿用冻结负荷");
        assertEquals(600, newView.totalCapacityKw());
        assertEquals(1, newView.trucks().stream().filter(t -> t.arrivedAt() != null).count(),
                "已到场记录随方案保留");

        // 旧单迟到到场只记事件
        ArrivalResponse late = dispatches.arrive(created.id(),
                new ArrivalRequest("gd-12-late", (long) (v + 1), t2, D0.plusSeconds(2100)));
        assertEquals("LATE", late.outcome());
        assertEquals(0, count("active_occupation", "truck_id = ?", t2), "迟到不再次占车");

        // 新单车到齐后供电
        ArrivalResponse all = dispatches.arrive(re.newDispatchId(),
                new ArrivalRequest("gd-12-a2", 1L, t4, D0.plusSeconds(2200)));
        assertEquals("ALL_ARRIVED", all.outcome());
        assertEquals(1, count("supply_evidence", "dispatch_id = ?", re.newDispatchId()));
    }

    @Test
    void 未超时不可改派且改派容量不足整单拒绝() {
        freezeAt(T0);
        long order = newOrder();
        long t1 = seedTruck(200, "F01");
        long t3 = seedTruck(150, "F02");
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-13", order, 1L, List.of(new LoadItem("市一医院", 200)), List.of(t1), D0));
        assertThrows(BusinessException.class, () -> dispatches.reassign(created.id(),
                new ReassignRequest("gd-13-r", 1L, List.of(t3), D0)), "未超时不可改派");

        freezeAt(T0.plusMinutes(30));
        dispatches.evaluateTimeouts();
        int v = jdbc.queryForObject("SELECT version FROM generator_dispatch WHERE id = ?",
                Integer.class, created.id());
        assertThrows(BusinessException.class, () -> dispatches.reassign(created.id(),
                new ReassignRequest("gd-13-r2", (long) v, List.of(t3), D0.plusSeconds(100))),
                "新方案容量不足应整单拒绝");
        assertEquals("TIMED_OUT", dispatchStatus(created.id()), "改派失败旧单保持超时态");
        assertEquals(1, count("active_occupation", "truck_id = ?", t1), "改派失败原占用不变");
        assertEquals(0, count("active_occupation", "truck_id = ?", t3));
    }

    @Test
    void 正式复电原子撤销调度释放车辆并保留供电证据() {
        freezeAt(T0);
        long order = newOrder();
        dispatch.dispatch(new DispatchRequest("gd-14-d", order, teamId("抢修一班")));
        long t1 = seedTruck(200, "F01");
        GeneratorDispatchView created = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-14", order, (long) orderVersion(order), List.of(new LoadItem("市一医院", 150)),
                List.of(t1), D0));
        dispatches.arrive(created.id(), new ArrivalRequest("gd-14-a", 1L, t1, D0.plusSeconds(300)));
        assertEquals(1, count("supply_evidence", "dispatch_id = ?", created.id()));

        restore.confirm(new RestoreConfirmRequest("gd-14-c1", order, "DISPATCHER", "调度员甲", null));
        restore.confirm(new RestoreConfirmRequest("gd-14-c2", order, "FIELD", "现场员乙",
                new BigDecimal("150")));

        assertEquals("CANCELLED", dispatchStatus(created.id()));
        assertEquals(0, count("active_occupation", "truck_id = ?", t1), "复电后车辆应释放");
        assertEquals(1, count("supply_evidence", "dispatch_id = ?", created.id()), "供电证据保留");
        assertTrue(restore != null);
    }

    @Test
    void 工单合并原子撤销未结束调度() {
        freezeAt(T0);
        long orderA = newOrder();
        long orderB = newOrder();
        long t1 = seedTruck(200, "F01");
        long t2 = seedTruck(300, "F01");
        GeneratorDispatchView da = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-15a", orderA, 1L, List.of(new LoadItem("市一医院", 100)), List.of(t1), D0));
        GeneratorDispatchView db = dispatches.create(new CreateGeneratorDispatchRequest(
                "gd-15b", orderB, 1L, List.of(new LoadItem("市一医院", 100)), List.of(t2), D0));

        workOrders.merge(new MergeRequest(List.of(orderA, orderB)));

        assertEquals("CANCELLED", dispatchStatus(da.id()));
        assertEquals("CANCELLED", dispatchStatus(db.id()));
        assertEquals(0, count("active_occupation", "1 = 1"), "合并后所有车辆应释放");
    }
}
