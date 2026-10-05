package com.example.starter.hazmat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.hazmat.clock.MutableClock;
import com.example.starter.hazmat.dto.ApplyRequest;
import com.example.starter.hazmat.dto.CheckinRequest;
import com.example.starter.hazmat.dto.RelayCheckinRequest;
import com.example.starter.hazmat.dto.RelayRequest;
import com.example.starter.hazmat.dto.RerouteRequest;
import com.example.starter.hazmat.repo.HazmatRepository;
import com.example.starter.hazmat.service.HazmatService;
import com.example.starter.hazmat.service.ServiceResult;

/**
 * H2 集成测试基类：复用完整业务入口（Service 即真实事务边界），不替换数据库。
 */
@SpringBootTest
abstract class HazmatIntegrationTestBase {

    @Autowired
    protected HazmatService service;

    @Autowired
    protected HazmatRepository repository;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JdbcTemplate jdbc;

    /** 场景间清理业务数据并复位基础数据与时钟，避免顺序依赖。 */
    @BeforeEach
    void cleanUp() {
        jdbc.execute("DELETE FROM idempotent_record");
        jdbc.execute("DELETE FROM relay_checkin");
        jdbc.execute("DELETE FROM shipment_active_relay");
        jdbc.execute("DELETE FROM relay_segment");
        jdbc.execute("DELETE FROM disposal_record");
        jdbc.execute("DELETE FROM checkin_record");
        jdbc.execute("DELETE FROM approval_waypoint");
        jdbc.execute("DELETE FROM route_approval");
        jdbc.execute("DELETE FROM shipment_event");
        jdbc.execute("DELETE FROM shipment");
        jdbc.execute("DELETE FROM vehicle_active_shipment");
        jdbc.execute("UPDATE vehicle SET frozen = 0, frozen_shipment_id = NULL, frozen_at = NULL");
        clock.reset();
    }

    protected ServiceResult apply(String requestId, String plate, String category,
                                  String route, LocalDateTime departure) {
        return service.apply(new ApplyRequest(requestId, plate, category, route, departure));
    }

    protected ServiceResult approve(String shipmentNo) {
        return service.decideApproval(shipmentNo, true);
    }

    protected ServiceResult reject(String shipmentNo) {
        return service.decideApproval(shipmentNo, false);
    }

    protected ServiceResult dispatch(String shipmentNo, String requestId) {
        return service.dispatch(shipmentNo, requestId);
    }

    protected ServiceResult checkin(String shipmentNo, String requestId, int seq, LocalDateTime time) {
        return service.checkin(shipmentNo, new CheckinRequest(requestId, seq, time));
    }

    protected ServiceResult setRelay(String shipmentNo, String requestId, int seq) {
        return service.setRelayPoint(shipmentNo, new RelayRequest(requestId, seq));
    }

    protected ServiceResult relayCheckin(String shipmentNo, String requestId, String escortId,
                                         String role, LocalDateTime time) {
        return service.relayCheckin(shipmentNo, new RelayCheckinRequest(requestId, escortId, role, time));
    }

    protected ServiceResult reroute(String shipmentNo, String requestId, String route) {
        return service.reroute(shipmentNo, new RerouteRequest(requestId, route));
    }

    protected ServiceResult complete(String shipmentNo, String requestId) {
        return service.complete(shipmentNo, requestId);
    }

    protected ServiceResult scan(LocalDateTime at) {
        return service.scanTimeouts(at);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> view(String shipmentNo) {
        return service.getShipment(shipmentNo).body();
    }

    protected String shipmentNo(ServiceResult result) {
        return (String) result.body().get("shipmentNo");
    }

    protected String status(ServiceResult result) {
        return (String) result.body().get("status");
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> approvals(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("approvals");
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> waypointsOf(Map<String, Object> approvalView) {
        return (List<Map<String, Object>>) approvalView.get("waypoints");
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> events(Map<String, Object> view) {
        return (List<Map<String, Object>>) view.get("events");
    }

    protected Map<String, Object> currentApprovalView(String shipmentNo) {
        Map<String, Object> v = view(shipmentNo);
        return approvals(v).stream().filter(a -> Boolean.TRUE.equals(a.get("current"))).findFirst().orElseThrow();
    }

    protected Map<String, Object> waypoint(String shipmentNo, int seq) {
        return waypointsOf(currentApprovalView(shipmentNo)).stream()
                .filter(w -> Integer.valueOf(seq).equals(w.get("seq"))).findFirst().orElseThrow();
    }
}
