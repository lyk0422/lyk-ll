package com.example.starter.marpol.web;

import com.example.starter.marpol.AbstractIntegrationTest;
import com.example.starter.marpol.support.GeoUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 经真实 HTTP 入口的端到端测试：提交、幂等重放/异参、修正、航次关闭、核查、签发、复查。
 */
class MarpolApiEndToEndTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private ObjectMapper objectMapper;

    private String shipImo;
    private String voyageNo;

    @BeforeEach
    void initRefs() {
        // 固定核查日 2026-09-25 UTC，整改截止日 2026-10-09。
        clock.setClock(java.time.Clock.fixed(
                java.time.Instant.parse("2026-09-25T10:00:00Z"), java.time.ZoneOffset.UTC));
        shipImo = "IMO-E2E-1";
        voyageNo = "V-E2E";
        post("/api/reference/special-areas?name=E2E-AREA",
                List.of(new double[]{0, 0}, new double[]{10, 0},
                        new double[]{10, 10}, new double[]{0, 10}), JsonNode.class);
        post("/api/reference/land-marks?name=E2E-LAND&longitude=0&latitude=0", null, JsonNode.class);
        post("/api/reference/ships", new CreateShipRequest(shipImo, "E2E 船"), JsonNode.class);
        post("/api/reference/voyages", new CreateVoyageRequest(shipImo, voyageNo), JsonNode.class);
    }

    @Test
    void fullFlowRecordCorrectInspectViolateAndIssue() {
        // 1) 合法油类记录（特殊区域外）
        JsonNode created = post("/api/records", record("REQ-E2E-1", "OIL", 20, 20), JsonNode.class).getBody();
        assertEquals(1, created.path("versionNo").asInt());
        assertEquals("ACTIVE", created.path("status").asText());
        long recordId = created.path("id").asLong();

        // 2) 同键同参重放：返回首次结果
        JsonNode replay = post("/api/records", record("REQ-E2E-1", "OIL", 20, 20), JsonNode.class).getBody();
        assertEquals(recordId, replay.path("id").asLong());

        // 3) 同键异参：409
        ResponseEntity<JsonNode> conflict = rawPost("/api/records",
                record("REQ-E2E-1", "OIL", 21, 20));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());

        // 4) 油类在区域内/边界：422
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                rawPost("/api/records", record("REQ-E2E-2", "OIL", 5, 5)).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                rawPost("/api/records", record("REQ-E2E-3", "OIL", 5, 0)).getStatusCode());

        // 5) 垃圾：恰 12 海里合法，小于 12 海里 422
        double exact12 = Math.toDegrees(12.0 / GeoUtils.EARTH_RADIUS_NM);
        assertEquals(HttpStatus.CREATED,
                rawPost("/api/records", record("REQ-E2E-4", "GARBAGE", 0, exact12)).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                rawPost("/api/records", record("REQ-E2E-5", "GARBAGE", 0, exact12 - 0.01)).getStatusCode());

        // 6) 修正产生 v2，原版本可查
        JsonNode corrected = post("/api/records/corrections", new CorrectRecordRequest(
                recordId, "REQ-E2E-C1", "OIL", 22, 22,
                java.time.Instant.parse("2026-09-25T08:00:00Z"),
                new java.math.BigDecimal("2.500"), "TON"), JsonNode.class).getBody();
        assertEquals(2, corrected.path("versionNo").asInt());
        assertEquals(recordId, corrected.path("supersedesId").asLong());

        JsonNode v1 = rest.getForObject("/api/records/{id}?version=1", JsonNode.class, recordId);
        assertEquals("SUPERSEDED", v1.path("status").asText());
        JsonNode latest = rest.getForObject("/api/records/{id}", JsonNode.class, recordId);
        assertEquals(2, latest.path("versionNo").asInt());

        // 7) 关闭航次后新增被拒绝
        rest.postForEntity("/api/reference/voyages/close?shipImo={s}&voyageNo={v}",
                null, JsonNode.class, shipImo, voyageNo);
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY,
                rawPost("/api/records", record("REQ-E2E-6", "OIL", 30, 30)).getStatusCode());

        // 8) 发起核查 → 标注违规 → 签发
        JsonNode inspection = post("/api/inspections",
                new StartInspectionRequest(shipImo, "PSCO-ZHANG"), JsonNode.class).getBody();
        String inspectionNo = inspection.path("inspectionNo").asText();

        ResponseEntity<JsonNode> decision = rawPost("/api/inspections/" + inspectionNo + "/items",
                new DecisionRequest(recordId, "VIOLATION", "油类记录与实际不符"));
        assertEquals(HttpStatus.OK, decision.getStatusCode());
        assertEquals("2026-10-09", decision.getBody().path("dueDate").asText(),
                "核查日 2026-09-25 + 14 自然日应为 2026-10-09");
        long itemId = decision.getBody().path("id").asLong();

        // 9) 签发为终态；重复签发幂等成功；签发后改结论 409
        ResponseEntity<JsonNode> issue1 = rawPost("/api/inspections/" + inspectionNo + "/issue", null);
        assertEquals(HttpStatus.OK, issue1.getStatusCode());
        assertEquals("ISSUED", issue1.getBody().path("status").asText());
        ResponseEntity<JsonNode> issue2 = rawPost("/api/inspections/" + inspectionNo + "/issue", null);
        assertEquals(HttpStatus.OK, issue2.getStatusCode());
        assertEquals(HttpStatus.CONFLICT,
                rawPost("/api/inspections/" + inspectionNo + "/items",
                        new DecisionRequest(recordId, "CONFIRMED", "试图翻案")).getStatusCode());

        // 10) 恰第 14 日整改关闭有效
        ResponseEntity<JsonNode> rectified = rawPost(
                "/api/inspections/" + inspectionNo + "/items/" + itemId + "/rectify", null);
        assertEquals(HttpStatus.OK, rectified.getStatusCode());
        assertEquals("RECTIFIED", rectified.getBody().path("itemStatus").asText());

        // 11) 补充证据走复查，引用原核查号
        ResponseEntity<JsonNode> reviewResp = rawPost("/api/inspections/reviews",
                new ReviewRequest(inspectionNo, itemId, "补充第三方检测报告"));
        assertEquals(HttpStatus.CREATED, reviewResp.getStatusCode());
        assertEquals("补充第三方检测报告", reviewResp.getBody().path("evidence").asText());

        JsonNode finalView = rest.getForObject("/api/inspections/{no}", JsonNode.class, inspectionNo);
        assertEquals("ISSUED", finalView.path("status").asText());
        assertEquals("VIOLATION", finalView.path("items").get(0).path("decision").asText());
        assertEquals(1, finalView.path("reviews").size());
        assertEquals("补充第三方检测报告",
                finalView.path("reviews").get(0).path("evidence").asText());
        assertTrue(rectified.getBody().path("itemStatus").asText().equals("RECTIFIED"));
    }

    private Map<String, Object> record(String requestId, String type, double lon, double lat) {
        return Map.of(
                "requestId", requestId,
                "shipImo", shipImo,
                "voyageNo", voyageNo,
                "type", type,
                "longitude", lon,
                "latitude", lat,
                "eventTime", "2026-09-25T08:00:00Z",
                "quantity", 1.5,
                "unit", "TON");
    }

    private <T> ResponseEntity<T> post(String path, Object body, Class<T> responseType) {
        return rest.postForEntity(path, body, responseType);
    }

    private ResponseEntity<JsonNode> rawPost(String path, Object body) {
        RequestEntity.BodyBuilder builder = RequestEntity.post(URI.create(resolve(path)))
                .contentType(MediaType.APPLICATION_JSON);
        RequestEntity<?> request = body == null
                ? builder.build()
                : builder.body(objectMapper.valueToTree(body));
        return rest.exchange(request, JsonNode.class);
    }

    private String resolve(String path) {
        return path.startsWith("http") ? path : path;
    }
}
