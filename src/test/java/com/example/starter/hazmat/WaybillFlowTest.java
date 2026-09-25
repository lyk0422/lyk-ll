package com.example.starter.hazmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.example.starter.hazmat.domain.WaybillStatus;
import com.example.starter.hazmat.error.BusinessException;
import com.example.starter.hazmat.error.ConflictException;
import com.example.starter.hazmat.repo.VehicleFreezeRepository;
import com.example.starter.hazmat.service.WaybillService;
import com.example.starter.hazmat.support.HazmatIntegrationTest;
import com.example.starter.hazmat.web.WaybillView;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 运单申请/核准/发运/核销主流程与规则测试。
 */
class WaybillFlowTest extends HazmatIntegrationTest {

    @Autowired
    private WaybillService waybillService;

    @Autowired
    private VehicleFreezeRepository freezes;

    @Test
    void 申请核准发运核销全流程_重复核销幂等() {
        WaybillView applied = waybillService.apply("REQ-FLOW-1", "VEH-001", 2,
                routeId("R-HIGH"), BASE_TIME.plusHours(1));
        assertEquals(WaybillStatus.PENDING, applied.status());

        WaybillView approved = waybillService.approve(applied.id());
        assertEquals(WaybillStatus.APPROVED, approved.status());

        WaybillView departed = waybillService.depart(applied.id());
        assertEquals(WaybillStatus.IN_TRANSIT, departed.status());
        assertEquals(3, departed.waypoints().size());
        assertEquals(BASE_TIME.plusHours(1).plusMinutes(30),
                departed.waypoints().get(0).plannedPassTime());

        WaybillView closed = waybillService.close(applied.id());
        assertEquals(WaybillStatus.CLOSED, closed.status());
        assertNotNull(closed.closedAt());

        WaybillView closedAgain = waybillService.close(applied.id());
        assertEquals(WaybillStatus.CLOSED, closedAgain.status());
        assertEquals(closed.closedAt(), closedAgain.closedAt());
    }

    @Test
    void 申请幂等_同键同参返回首次结果() {
        WaybillView first = waybillService.apply("REQ-IDEM-1", "VEH-002", 1,
                routeId("R-LOW"), BASE_TIME.plusHours(2));
        WaybillView replay = waybillService.apply("REQ-IDEM-1", "VEH-002", 1,
                routeId("R-LOW"), BASE_TIME.plusHours(2));
        assertEquals(first.id(), replay.id());
        assertEquals(first.status(), replay.status());
    }

    @Test
    void 申请幂等_同键异参返回409() {
        waybillService.apply("REQ-IDEM-2", "VEH-003", 1,
                routeId("R-LOW"), BASE_TIME.plusHours(2));
        assertThrows(ConflictException.class, () -> waybillService.apply(
                "REQ-IDEM-2", "VEH-003", 2, routeId("R-LOW"), BASE_TIME.plusHours(2)));
    }

    @Test
    void 同一车辆同时刻只允许一个执行中运单() {
        waybillService.apply("REQ-VEH-1", "VEH-004", 1,
                routeId("R-LOW"), BASE_TIME.plusHours(2));
        assertThrows(BusinessException.class, () -> waybillService.apply(
                "REQ-VEH-2", "VEH-004", 1, routeId("R-LOW"), BASE_TIME.plusHours(3)));
    }

    @Test
    void 冻结车辆禁止新申请() {
        freezes.freeze("VEH-005", 1L, "测试冻结", BASE_TIME);
        assertThrows(BusinessException.class, () -> waybillService.apply(
                "REQ-FRZ-1", "VEH-005", 1, routeId("R-LOW"), BASE_TIME.plusHours(2)));
    }

    @Test
    void 危化品等级高于路线通行等级_核准拒绝() {
        WaybillView applied = waybillService.apply("REQ-LVL-1", "VEH-006", 3,
                routeId("R-LOW"), BASE_TIME.plusHours(2));
        assertThrows(BusinessException.class, () -> waybillService.approve(applied.id()));
        assertEquals(WaybillStatus.REJECTED, waybillService.getView(applied.id()).status());
    }

    @Test
    void 管制时段起点边界_恰在开始时刻视为受管制() {
        // 计划发运 07:00，途经点 2 计划通过 08:00，恰为管制起点（含）→ 拒绝
        WaybillView applied = waybillService.apply("REQ-RST-1", "VEH-007", 2,
                routeId("R-RESTRICTED"), LocalDateTime.of(2026, 1, 1, 7, 0));
        assertThrows(BusinessException.class, () -> waybillService.approve(applied.id()));
        assertEquals(WaybillStatus.REJECTED, waybillService.getView(applied.id()).status());
    }

    @Test
    void 管制时段终点边界_恰在结束时刻视为不受管制() {
        // 计划发运 11:30，途经点 1 计划通过 12:00，恰为管制终点（不含）→ 放行
        WaybillView applied = waybillService.apply("REQ-RST-2", "VEH-008", 2,
                routeId("R-RESTRICTED"), LocalDateTime.of(2026, 1, 1, 11, 30));
        WaybillView approved = waybillService.approve(applied.id());
        assertEquals(WaybillStatus.APPROVED, approved.status());
    }

    @Test
    void 管制时段内_计划通过时刻落入窗口拒绝() {
        // 计划发运 10:30，途经点 1 计划通过 11:00，落入 [08:00, 12:00) → 拒绝
        WaybillView applied = waybillService.apply("REQ-RST-3", "VEH-009", 2,
                routeId("R-RESTRICTED"), LocalDateTime.of(2026, 1, 1, 10, 30));
        assertThrows(BusinessException.class, () -> waybillService.approve(applied.id()));
    }
}
