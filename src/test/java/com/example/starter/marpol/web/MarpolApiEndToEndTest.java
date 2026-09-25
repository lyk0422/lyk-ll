package com.example.starter.marpol.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.starter.marpol.MutableClockConfiguration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 真实 HTTP 端到端测试：记录提交、幂等重放/异参、版本化修正、
 * 港口核查标注违规、签发终态、航次关闭限制。
 */
@SpringBootTest(classes = {com.example.starter.StarterApplication.class,
        MutableClockConfiguration.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MarpolApiEndToEndTest {

    private static final Instant EVENT_TIME = Instant.parse("2026-09-01T08:00:00Z");

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM defect");
        jdbc.update("DELETE FROM inspection_item");
        jdbc.update("DELETE FROM inspection");
        jdbc.update("DELETE FROM emission_request");
        jdbc.update("DELETE FROM emission_record");
        jdbc.update("DELETE FROM voyage");
        jdbc.update("DELETE FROM ship");
        jdbc.update("INSERT INTO ship (ship_id, ship_name, created_at) "
                + "VALUES ('SHIP-001', '海峰轮（合成）', '2026-01-01 00:00:00')");
        jdbc.update("INSERT INTO voyage (ship_id, voyage_no, status, closed_at, created_at) "
                + "VALUES ('SHIP-001', 'V-2026-01', 'OPEN', NULL, '2026-01-01 00:00:00')");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return response.getBody();
    }

    @Test
    void fullFlowSubmitAmendMarkViolationAndIssue() {
        // 1. 提交一条特殊区域外的油类排放（提交时合规）
        Map<String, Object> submit = Map.of(
                "requestId", "REQ-E2E-1",
                "shipId", "SHIP-001",
                "voyageNo", "V-2026-01",
                "dischargeType", "OIL",
                "latitude", new BigDecimal("5"),
                "longitude", new BigDecimal("115"),
                "eventTime", EVENT_TIME.toString(),
                "quantity", new BigDecimal("2.500"),
                "unit", "m3");
        ResponseEntity<Map> created = rest.postForEntity(
                "/api/discharges", new HttpEntity<>(submit), Map.class);
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        Map<String, Object> record = body(created);
        assertEquals(1, ((Number) record.get("version")).intValue());
        assertEquals(Boolean.TRUE, record.get("compliant"));
        String recordNo = (String) record.get("recordNo");

        // 同键同参重放：返回首次结果
        ResponseEntity<Map> replay = rest.postForEntity(
                "/api/discharges", new HttpEntity<>(submit), Map.class);
        assertEquals(HttpStatus.CREATED, replay.getStatusCode());
        assertEquals(recordNo, body(replay).get("recordNo"));
        assertEquals(Boolean.TRUE, body(replay).get("idempotentReplay"));

        // 同键异参：409
        Map<String, Object> altered = new java.util.HashMap<>(submit);
        altered.put("quantity", new BigDecimal("9.999"));
        ResponseEntity<Map> conflict = rest.postForEntity(
                "/api/discharges", new HttpEntity<>(altered), Map.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        // 2. 修正：生成版本 2，引用版本 1
        Map<String, Object> amend = new java.util.HashMap<>();
        amend.put("requestId", "REQ-E2E-AMEND");
        amend.put("recordNo", recordNo);
        amend.put("basedOnVersion", 1);
        amend.put("shipId", "SHIP-001");
        amend.put("voyageNo", "V-2026-01");
        amend.put("dischargeType", "OIL");
        amend.put("latitude", new BigDecimal("6"));
        amend.put("longitude", new BigDecimal("115"));
        amend.put("eventTime", EVENT_TIME.toString());
        amend.put("quantity", new BigDecimal("3.000"));
        amend.put("unit", "m3");
        ResponseEntity<Map> amended = rest.postForEntity(
                "/api/discharges/amend", new HttpEntity<>(amend), Map.class);
        assertEquals(HttpStatus.CREATED, amended.getStatusCode());
        assertEquals(2, ((Number) body(amended).get("version")).intValue());
        assertEquals(recordNo, body(amended).get("supersedesRecordNo"));
        assertEquals(1, ((Number) body(amended).get("supersedesVersion")).intValue());

        // 默认查询返回最新；指定版本 1 可查历史
        ResponseEntity<Map> latest = rest.getForEntity(
                "/api/discharges/" + recordNo, Map.class);
        assertEquals(2, ((Number) body(latest).get("version")).intValue());
        assertEquals("ACTIVE", body(latest).get("status"));
        ResponseEntity<Map> v1 = rest.getForEntity(
                "/api/discharges/" + recordNo + "?version=1", Map.class);
        assertEquals(1, ((Number) body(v1).get("version")).intValue());
        assertEquals("SUPERSEDED", body(v1).get("status"));

        // 3. 发起核查并标注违规（生成缺陷，期限=核查日+14）
        Map<String, Object> inspectionReq = Map.of(
                "shipId", "SHIP-001",
                "portCode", "P-SYN-01",
                "inspectDate", LocalDate.of(2026, 9, 10).toString());
        ResponseEntity<Map> inspection = rest.postForEntity(
                "/api/inspections", new HttpEntity<>(inspectionReq), Map.class);
        assertEquals(HttpStatus.CREATED, inspection.getStatusCode());
        String inspectionNo = (String) body(inspection).get("inspectionNo");

        Map<String, Object> decision = Map.of(
                "recordNo", recordNo,
                "conclusion", "VIOLATION",
                "evidence", "油水分离器记录异常（合成）");
        ResponseEntity<Map> item = rest.postForEntity(
                "/api/inspections/" + inspectionNo + "/decisions",
                new HttpEntity<>(decision), Map.class);
        assertEquals(HttpStatus.OK, item.getStatusCode());
        assertEquals("VIOLATION", body(item).get("conclusion"));
        assertEquals("2026-09-24", body(item).get("defectDeadline"));

        // 4. 签发为终态
        ResponseEntity<Map> issued = rest.postForEntity(
                "/api/inspections/" + inspectionNo + "/issue", null, Map.class);
        assertEquals(HttpStatus.OK, issued.getStatusCode());
        assertEquals("ISSUED", body(issued).get("status"));

        // 重复签发幂等成功
        ResponseEntity<Map> reissue = rest.postForEntity(
                "/api/inspections/" + inspectionNo + "/issue", null, Map.class);
        assertEquals(HttpStatus.OK, reissue.getStatusCode());
        assertEquals("ISSUED", body(reissue).get("status"));

        // 签发后修改结论一律拒绝
        Map<String, Object> change = Map.of(
                "recordNo", recordNo, "conclusion", "CONFIRM");
        ResponseEntity<Map> rejected = rest.postForEntity(
                "/api/inspections/" + inspectionNo + "/decisions",
                new HttpEntity<>(change), Map.class);
        assertEquals(HttpStatus.CONFLICT, rejected.getStatusCode());

        // 5. 航次关闭后新增记录拒绝
        ResponseEntity<Map> closed = rest.postForEntity(
                "/api/voyages/close?shipId=SHIP-001&voyageNo=V-2026-01",
                null, Map.class);
        assertEquals(HttpStatus.OK, closed.getStatusCode());
        assertEquals("CLOSED", body(closed).get("status"));
        ResponseEntity<Map> afterClose = rest.postForEntity(
                "/api/discharges", new HttpEntity<>(Map.of(
                        "requestId", "REQ-E2E-CLOSED",
                        "shipId", "SHIP-001",
                        "voyageNo", "V-2026-01",
                        "dischargeType", "OIL",
                        "latitude", new BigDecimal("5"),
                        "longitude", new BigDecimal("115"),
                        "eventTime", EVENT_TIME.toString(),
                        "quantity", new BigDecimal("1.000"),
                        "unit", "m3")), Map.class);
        assertEquals(HttpStatus.CONFLICT, afterClose.getStatusCode());
        assertFalse(afterClose.getStatusCode().is2xxSuccessful());
        assertTrue(closed.getStatusCode().is2xxSuccessful());
    }

    @Test
    void garbageExactlyTwelveNmIsAcceptedOverHttp() {
        double earthRadiusNm = 6_371_000.0 / 1852.0;
        BigDecimal lat = BigDecimal.valueOf(30 + Math.toDegrees(12.0 / earthRadiusNm));
        Map<String, Object> garbage = Map.of(
                "requestId", "REQ-E2E-G12",
                "shipId", "SHIP-001",
                "voyageNo", "V-2026-01",
                "dischargeType", "GARBAGE",
                "latitude", lat,
                "longitude", new BigDecimal("122"),
                "eventTime", EVENT_TIME.toString(),
                "quantity", new BigDecimal("10.000"),
                "unit", "kg");
        ResponseEntity<Map> response = rest.postForEntity(
                "/api/discharges", new HttpEntity<>(garbage), Map.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(Boolean.TRUE, body(response).get("compliant"),
                String.valueOf(body(response).get("complianceDetail")));
    }
}
