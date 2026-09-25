package com.example.starter.hazmat.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.starter.hazmat.clock.MutableClock;

/**
 * 真实 HTTP 入口测试：完整 JSON 序列化、HTTP 状态码（200/400/409/422）与幂等重放。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HazmatControllerHttpTest {

    private static final LocalDateTime DEPARTURE = LocalDateTime.parse("2026-10-02T08:00:00");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MutableClock clock;

    private String base;

    @BeforeEach
    void clean() {
        base = "http://localhost:" + port;
        jdbc.execute("DELETE FROM idempotent_record");
        jdbc.execute("DELETE FROM checkin_record");
        jdbc.execute("DELETE FROM approval_waypoint");
        jdbc.execute("DELETE FROM route_approval");
        jdbc.execute("DELETE FROM shipment_event");
        jdbc.execute("DELETE FROM shipment");
        jdbc.execute("DELETE FROM vehicle_active_shipment");
        jdbc.execute("UPDATE vehicle SET frozen = 0, frozen_shipment_id = NULL, frozen_at = NULL");
        clock.reset();
    }

    private HttpEntity<Map<String, Object>> json(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> resp) {
        return resp.getBody();
    }

    @Test
    void fullFlowOverHttp() {
        // 1) 申请
        Map<String, Object> applyBody = Map.of(
                "requestId", "http-req-1",
                "vehiclePlate", "沪A-1001",
                "goodsCategory", "FLAMMABLE",
                "routeCode", "R-G3",
                "plannedDeparture", DEPARTURE.toString());
        ResponseEntity<Map> applied = rest.postForEntity(base + "/api/shipments/apply",
                json(applyBody), Map.class);
        assertThat(applied.getStatusCode()).isEqualTo(HttpStatus.OK);
        String no = (String) body(applied).get("shipmentNo");
        assertThat(body(applied).get("status")).isEqualTo("APPLIED");

        // 2) 核准（禁行/等级不符时为 422，这里合法 → 200）
        ResponseEntity<Map> approved = rest.postForEntity(
                base + "/api/shipments/" + no + "/approvals/decide?approved=true",
                new HttpEntity<>("", new HttpHeaders()), Map.class);
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(approved).get("status")).isEqualTo("ROUTE_APPROVED");

        // 3) 发运
        ResponseEntity<Map> dispatched = rest.postForEntity(
                base + "/api/shipments/" + no + "/dispatch",
                json(Map.of("requestId", "http-disp-1")), Map.class);
        assertThat(dispatched.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4) 打卡
        ResponseEntity<Map> checked = rest.postForEntity(
                base + "/api/shipments/" + no + "/checkins",
                json(Map.of("requestId", "http-ck-0", "waypointSeq", 0,
                        "checkTime", DEPARTURE.plusMinutes(32).toString())), Map.class);
        assertThat(checked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(checked).get("status")).isEqualTo("IN_TRANSIT");

        // 5) 改线申请 + 核准原子切换
        ResponseEntity<Map> rerouted = rest.postForEntity(
                base + "/api/shipments/" + no + "/reroute",
                json(Map.of("requestId", "http-rr-1", "routeCode", "R-G2")), Map.class);
        assertThat(rerouted.getStatusCode()).isEqualTo(HttpStatus.OK);
        Number pendingId = (Number) body(rerouted).get("pendingApprovalId");
        ResponseEntity<Map> switched = rest.postForEntity(
                base + "/api/shipments/" + no + "/approvals/" + pendingId.longValue() + "/approve",
                new HttpEntity<>("", new HttpHeaders()), Map.class);
        assertThat(switched.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 6) 核销结案
        ResponseEntity<Map> completed = rest.postForEntity(
                base + "/api/shipments/" + no + "/complete",
                json(Map.of("requestId", "http-done-1")), Map.class);
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(completed).get("status")).isEqualTo("COMPLETED");

        // 详情可查。
        ResponseEntity<Map> detail = rest.getForEntity(base + "/api/shipments/" + no, Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(detail).get("terminal")).isEqualTo(true);
    }

    @Test
    void rejectionAndValidationOverHttp() {
        // 禁行路线核准 → 422。
        Map<String, Object> applyBody = Map.of(
                "requestId", "http-ban",
                "vehiclePlate", "沪A-1002",
                "goodsCategory", "TOXIC",
                "routeCode", "R-BAN",
                "plannedDeparture", DEPARTURE.toString());
        ResponseEntity<Map> applied = rest.postForEntity(base + "/api/shipments/apply",
                json(applyBody), Map.class);
        String no = (String) body(applied).get("shipmentNo");
        ResponseEntity<Map> rejected = rest.postForEntity(
                base + "/api/shipments/" + no + "/approvals/decide?approved=true",
                new HttpEntity<>("", new HttpHeaders()), Map.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(body(rejected).get("errorCode")).isEqualTo("ROUTE_REJECTED");

        // 缺字段 → 400 校验错误。
        ResponseEntity<Map> invalid = rest.postForEntity(base + "/api/shipments/apply",
                json(Map.of("requestId", "http-invalid")), Map.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(invalid).get("code")).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void sameKeyDifferentParamsReturns409OverHttp() {
        Map<String, Object> first = Map.of(
                "requestId", "http-conf",
                "vehiclePlate", "沪B-2001",
                "goodsCategory", "TOXIC",
                "routeCode", "R-G1",
                "plannedDeparture", DEPARTURE.toString());
        assertThat(rest.postForEntity(base + "/api/shipments/apply", json(first), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> changed = new java.util.HashMap<>(first);
        changed.put("routeCode", "R-G3");
        ResponseEntity<Map> conflict = rest.postForEntity(base + "/api/shipments/apply",
                json(changed), Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(conflict).get("code")).isEqualTo("IDEMPOTENT_FINGERPRINT_MISMATCH");
    }
}
