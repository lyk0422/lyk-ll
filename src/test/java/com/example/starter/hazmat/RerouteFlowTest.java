package com.example.starter.hazmat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.hazmat.domain.RerouteStatus;
import com.example.starter.hazmat.domain.WaybillStatus;
import com.example.starter.hazmat.domain.WaypointStatus;
import com.example.starter.hazmat.error.ConflictException;
import com.example.starter.hazmat.service.CheckinService;
import com.example.starter.hazmat.service.RerouteService;
import com.example.starter.hazmat.service.WaybillService;
import com.example.starter.hazmat.support.HazmatIntegrationTest;
import com.example.starter.hazmat.web.RerouteView;
import com.example.starter.hazmat.web.WaybillView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 紧急改线测试：申请-核准两段式；核准前原路线打卡继续有效；
 * 改线生效与原路线终止在同一事务内完成。
 */
class RerouteFlowTest extends HazmatIntegrationTest {

    @Autowired
    private WaybillService waybillService;

    @Autowired
    private CheckinService checkinService;

    @Autowired
    private RerouteService rerouteService;

    private long departWaybill(String requestId, String vehicle) {
        WaybillView applied = waybillService.apply(requestId, vehicle, 1,
                routeId("R-HIGH"), BASE_TIME.plusHours(1));
        waybillService.approve(applied.id());
        waybillService.depart(applied.id());
        return applied.id();
    }

    @Test
    void 改线核准前原路线打卡继续有效() {
        long waybillId = departWaybill("REQ-RR-1", "VEH-301");
        RerouteView reroute = rerouteService.apply("RR-1", waybillId, routeId("R-LOW"));
        assertEquals(RerouteStatus.PENDING, reroute.status());

        // 核准前：原路线（第 1 代）途经点打卡仍然有效
        assertTrue(checkinService.checkin("CK-RR-1", waybillId, 1,
                BASE_TIME.plusHours(2)).accepted());

        // 改线申请幂等：同键同参返回首次结果，同键异参 409
        RerouteView replay = rerouteService.apply("RR-1", waybillId, routeId("R-LOW"));
        assertEquals(reroute.id(), replay.id());
        assertThrows(ConflictException.class,
                () -> rerouteService.apply("RR-1", waybillId, routeId("R-HIGH")));
    }

    @Test
    void 改线生效与原路线终止原子完成() {
        long waybillId = departWaybill("REQ-RR-2", "VEH-302");
        checkinService.checkin("CK-RR-2", waybillId, 1, BASE_TIME.plusHours(2));
        RerouteView reroute = rerouteService.apply("RR-2", waybillId, routeId("R-LOW"));
        RerouteView approved = rerouteService.approve(reroute.id());
        assertEquals(RerouteStatus.APPROVED, approved.status());

        // 运单切换到新路线，代次 +1
        WaybillView view = waybillService.getView(waybillId);
        assertEquals(routeId("R-LOW"), view.routeId());
        assertEquals(2, view.routeGeneration());
        assertEquals(WaybillStatus.IN_TRANSIT, view.status());
        assertEquals(3, view.waypoints().size());
        assertTrue(view.waypoints().stream()
                .allMatch(wp -> wp.status() == WaypointStatus.PENDING));

        // 旧代次未打卡途经点已终止，已打卡途经点保留
        Long cancelled = jdbc.queryForObject(
                "SELECT COUNT(*) FROM waybill_waypoint WHERE waybill_id = ? AND generation = 1"
                        + " AND status = 'CANCELLED'",
                Long.class, waybillId);
        assertEquals(2, cancelled);
        Long checked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM waybill_waypoint WHERE waybill_id = ? AND generation = 1"
                        + " AND status = 'CHECKED'",
                Long.class, waybillId);
        assertEquals(1, checked);

        // 新路线途经点可以打卡（当前代次已从序号 1 重新编排）
        assertTrue(checkinService.checkin("CK-RR-3", waybillId, 1,
                BASE_TIME.plusHours(3)).accepted());
    }

    @Test
    void 运单结案后改线核准失败且原路线不受影响() {
        long waybillId = departWaybill("REQ-RR-3", "VEH-303");
        RerouteView reroute = rerouteService.apply("RR-3", waybillId, routeId("R-LOW"));
        waybillService.close(waybillId);

        // 核准时发现运单已结案 → 冲突，事务回滚：申请仍 PENDING，原途经点仍 PENDING
        assertThrows(ConflictException.class, () -> rerouteService.approve(reroute.id()));
        RerouteView after = rerouteService.apply("RR-3", waybillId, routeId("R-LOW"));
        assertEquals(RerouteStatus.PENDING, after.status());
        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM waybill_waypoint WHERE waybill_id = ? AND generation = 1"
                        + " AND status = 'PENDING'",
                Long.class, waybillId);
        assertEquals(3, pending);
    }
}
