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
        jdbc.execute("DELETE FROM escort_reservation");
        jdbc.execute("DELETE FROM escort_qualification");
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

    /**
     * 两张合成运单都打算在 23:45（Asia/Shanghai）由 ESC-B 接班：
     * 第一张预约成功，第二张同员重叠班段 409，且无人员占用不得记录换班完成。
     */
    @Test
    void personnelReservationOverHttp() {
        clock.setTime(LocalDateTime.parse("2026-10-02T22:30:00"));
        // 1) 新增 ESC-B 合成资格（UTC 有效区间），版本从 1 起。
        ResponseEntity<Map> qual = rest.postForEntity(base + "/api/escort-qualifications",
                json(Map.of("requestId", "http-qual-1", "escortId", "ESC-B",
                        "goodsCategory", "FLAMMABLE",
                        "validFromUtc", "2026-10-02T14:00:00", "validToUtc", "2026-10-03T03:00:00")),
                Map.class);
        assertThat(qual.getStatusCode()).isEqualTo(HttpStatus.OK);
        Number qualId = (Number) body(qual).get("qualificationId");
        assertThat(body(qual).get("versionNo")).isEqualTo(1);
        assertThat(rest.getForEntity(base + "/api/escort-qualifications/" + qualId.longValue(), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2) 两张运单（不同车辆）同路线同时刻发运，均启用人员管控的接力点 p1。
        String[] nos = new String[2];
        long[] segs = new long[2];
        String[] plates = {"沪A-1001", "沪A-1002"};
        for (int i = 0; i < 2; i++) {
            ResponseEntity<Map> applied = rest.postForEntity(base + "/api/shipments/apply",
                    json(Map.of("requestId", "http-pr-apply-" + i, "vehiclePlate", plates[i],
                            "goodsCategory", "FLAMMABLE", "routeCode", "R-G3",
                            "plannedDeparture", "2026-10-02T22:30:00")), Map.class);
            nos[i] = (String) body(applied).get("shipmentNo");
            rest.postForEntity(base + "/api/shipments/" + nos[i] + "/approvals/decide?approved=true",
                    new HttpEntity<>("", new HttpHeaders()), Map.class);
            rest.postForEntity(base + "/api/shipments/" + nos[i] + "/dispatch",
                    json(Map.of("requestId", "http-pr-disp-" + i)), Map.class);
            ResponseEntity<Map> rp = rest.postForEntity(
                    base + "/api/shipments/" + nos[i] + "/relay-points",
                    json(Map.of("requestId", "http-pr-rp-" + i, "waypointSeq", 1,
                            "personnelControl", true)), Map.class);
            assertThat(rp.getStatusCode()).isEqualTo(HttpStatus.OK);
            segs[i] = ((Number) body(rp).get("relaySegmentId")).longValue();
        }

        // 3) 第一张预约 ESC-B 班段 [15:30,16:00) UTC（=[23:30,24:00) CST）成功；第二张同人同段 409。
        ResponseEntity<Map> resA = rest.postForEntity(
                base + "/api/shipments/" + nos[0] + "/relay-segments/" + segs[0] + "/reservations",
                json(Map.of("requestId", "http-pr-res-a", "escortId", "ESC-B",
                        "shiftStartUtc", "2026-10-02T15:30:00", "shiftEndUtc", "2026-10-02T16:00:00")),
                Map.class);
        assertThat(resA.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> resB = rest.postForEntity(
                base + "/api/shipments/" + nos[1] + "/relay-segments/" + segs[1] + "/reservations",
                json(Map.of("requestId", "http-pr-res-b", "escortId", "ESC-B",
                        "shiftStartUtc", "2026-10-02T15:30:00", "shiftEndUtc", "2026-10-02T16:00:00")),
                Map.class);
        assertThat(resB.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(resB).get("code")).isEqualTo("ESCORT_SHIFT_OVERLAP");

        // 4) 冲突那张仍由原押运员负责：OUT 可离岗，IN 无预约被拒绝，换班不生效。
        rest.postForEntity(base + "/api/shipments/" + nos[1] + "/relay-checkins",
                json(Map.of("requestId", "http-pr-out-b", "escortId", "ESC-01", "role", "OUT",
                        "checkTime", "2026-10-02T23:40:00")), Map.class);
        ResponseEntity<Map> inB = rest.postForEntity(
                base + "/api/shipments/" + nos[1] + "/relay-checkins",
                json(Map.of("requestId", "http-pr-in-b", "escortId", "ESC-B", "role", "IN",
                        "checkTime", "2026-10-02T23:45:00")), Map.class);
        assertThat(inB.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(inB).get("code")).isEqualTo("RESERVATION_REQUIRED");

        // 5) 第一张 23:45 由预约人员接班，换班完成，预约核销。
        rest.postForEntity(base + "/api/shipments/" + nos[0] + "/relay-checkins",
                json(Map.of("requestId", "http-pr-out-a", "escortId", "ESC-01", "role", "OUT",
                        "checkTime", "2026-10-02T23:40:00")), Map.class);
        ResponseEntity<Map> inA = rest.postForEntity(
                base + "/api/shipments/" + nos[0] + "/relay-checkins",
                json(Map.of("requestId", "http-pr-in-a", "escortId", "ESC-B", "role", "IN",
                        "checkTime", "2026-10-02T23:45:00")), Map.class);
        assertThat(inA.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(inA).get("handoverCompleted")).isEqualTo(true);
        ResponseEntity<Map> detailA = rest.getForEntity(base + "/api/shipments/" + nos[0], Map.class);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> resvs =
                (java.util.List<Map<String, Object>>) body(detailA).get("reservations");
        assertThat(resvs).hasSize(1);
        assertThat(resvs.get(0).get("status")).isEqualTo("CONSUMED");
        assertThat(resvs.get(0).get("qualificationVersion")).isEqualTo(1);

        // 6) 换班完成后撤销资格：不回滚，留风险证据，版本加 1。
        ResponseEntity<Map> revoked = rest.postForEntity(
                base + "/api/escort-qualifications/" + qualId.longValue() + "/revoke",
                json(Map.of("requestId", "http-pr-rvk", "expectedVersion", 1)), Map.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(revoked).get("revoked")).isEqualTo(true);
        assertThat(body(revoked).get("versionNo")).isEqualTo(2);
        ResponseEntity<Map> detailAfter = rest.getForEntity(base + "/api/shipments/" + nos[0], Map.class);
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> events =
                (java.util.List<Map<String, Object>>) body(detailAfter).get("events");
        assertThat(events.stream().anyMatch(e -> "QUALIFICATION_REVOKED_RISK".equals(e.get("eventType"))))
                .isTrue();
    }
}
