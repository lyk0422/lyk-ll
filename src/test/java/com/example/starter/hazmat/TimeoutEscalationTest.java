package com.example.starter.hazmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.hazmat.domain.AlertType;
import com.example.starter.hazmat.domain.WaybillStatus;
import com.example.starter.hazmat.domain.WaypointStatus;
import com.example.starter.hazmat.error.BusinessException;
import com.example.starter.hazmat.error.ConflictException;
import com.example.starter.hazmat.repo.VehicleFreezeRepository;
import com.example.starter.hazmat.service.CheckinService;
import com.example.starter.hazmat.service.TimeoutScanService;
import com.example.starter.hazmat.service.WaybillService;
import com.example.starter.hazmat.support.HazmatIntegrationTest;
import com.example.starter.hazmat.support.TestClockConfiguration;
import com.example.starter.hazmat.web.WaybillView;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 途经点超时与失联升级测试：计划通过时刻 + 60 分钟（恰满判定）未打卡标记偏离预警，
 * 连续 2 点超时（恰 2 个触发）升级失联并冻结车辆。
 */
class TimeoutEscalationTest extends HazmatIntegrationTest {

    @Autowired
    private WaybillService waybillService;

    @Autowired
    private CheckinService checkinService;

    @Autowired
    private TimeoutScanService scanService;

    @Autowired
    private VehicleFreezeRepository freezes;

    /** 发运：计划发运 = 基准 + 1 小时；途经点计划通过 = +90/+120/+150 分钟（相对基准）。 */
    private long departWaybill(String requestId, String vehicle) {
        WaybillView applied = waybillService.apply(requestId, vehicle, 1,
                routeId("R-LOW"), BASE_TIME.plusHours(1));
        waybillService.approve(applied.id());
        waybillService.depart(applied.id());
        return applied.id();
    }

    private void advanceHoursMinutes(long hours, long minutes) {
        clock.setInstant(TestClockConfiguration.BASE_INSTANT
                .plus(Duration.ofHours(hours)).plus(Duration.ofMinutes(minutes)));
    }

    @Test
    void 恰满60分钟判定超时_差1分钟不判() {
        long waybillId = departWaybill("REQ-TO-1", "VEH-201");
        // 途经点 1 计划通过 基准+1:30，超时线 基准+2:30
        advanceHoursMinutes(2, 29);
        scanService.scanOnce();
        assertEquals(WaypointStatus.PENDING,
                waybillService.getView(waybillId).waypoints().get(0).status());

        advanceHoursMinutes(2, 30);
        scanService.scanOnce();
        WaybillView view = waybillService.getView(waybillId);
        assertEquals(WaypointStatus.TIMEOUT, view.waypoints().get(0).status());
        assertEquals(1, countAlert(waybillId, AlertType.DEVIATION));
        // 仅 1 点超时，不升级失联
        assertEquals(WaybillStatus.IN_TRANSIT, view.status());
        assertFalse(freezes.isFrozen("VEH-201"));
    }

    @Test
    void 连续2点超时升级失联并冻结() {
        long waybillId = departWaybill("REQ-TO-2", "VEH-202");
        // 推进到途经点 2 超时线（基准+3:00）：途经点 1、2 连续超时
        advanceHoursMinutes(3, 0);
        scanService.scanOnce();

        WaybillView view = waybillService.getView(waybillId);
        assertEquals(WaybillStatus.LOST, view.status());
        assertEquals(WaypointStatus.TIMEOUT, view.waypoints().get(0).status());
        assertEquals(WaypointStatus.TIMEOUT, view.waypoints().get(1).status());
        assertTrue(freezes.isFrozen("VEH-202"));
        assertEquals(1, countAlert(waybillId, AlertType.LOST));

        // 冻结后新申请被拒绝
        assertThrows(BusinessException.class, () -> waybillService.apply(
                "REQ-TO-2B", "VEH-202", 1, routeId("R-LOW"), BASE_TIME.plusHours(5)));
        // 失联后核销冲突（终态互斥）
        assertThrows(ConflictException.class, () -> waybillService.close(waybillId));
        // 失联后不再受理打卡
        assertThrows(ConflictException.class, () -> checkinService.checkin(
                "CK-TO-2", waybillId, 3, BASE_TIME.plusHours(4)));
    }

    @Test
    void 非连续超时不升级失联() {
        long waybillId = departWaybill("REQ-TO-3", "VEH-203");
        // 途经点 2 按时打卡（途经点 1 未打卡，乱序归位）
        checkinService.checkin("CK-TO-3", waybillId, 2, BASE_TIME.plusHours(2));
        // 推进到途经点 3 超时线（基准+3:30）：途经点 1、3 超时但中间已打卡，不连续
        advanceHoursMinutes(3, 30);
        scanService.scanOnce();

        WaybillView view = waybillService.getView(waybillId);
        assertEquals(WaypointStatus.TIMEOUT, view.waypoints().get(0).status());
        assertEquals(WaypointStatus.CHECKED, view.waypoints().get(1).status());
        assertEquals(WaypointStatus.TIMEOUT, view.waypoints().get(2).status());
        assertEquals(WaybillStatus.IN_TRANSIT, view.status());
        assertFalse(freezes.isFrozen("VEH-203"));
    }

    @Test
    void 恰2个连续超时即触发_第3点无关() {
        long waybillId = departWaybill("REQ-TO-4", "VEH-204");
        // 途经点 3 按时打卡；途经点 1、2 连续超时（恰 2 个）即升级
        checkinService.checkin("CK-TO-4", waybillId, 3, BASE_TIME.plusHours(2).plusMinutes(40));
        advanceHoursMinutes(3, 0);
        scanService.scanOnce();

        WaybillView view = waybillService.getView(waybillId);
        assertEquals(WaybillStatus.LOST, view.status());
        assertTrue(freezes.isFrozen("VEH-204"));
    }

    private long countAlert(long waybillId, AlertType type) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM alert WHERE waybill_id = ? AND type = ?",
                Long.class, waybillId, type.name());
        return count == null ? 0 : count;
    }
}
