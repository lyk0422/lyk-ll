package com.example.starter.slot;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST 入口测试：验证状态码、错误语义与公开主流程。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SlotApiTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM slot_allocation");
        jdbc.update("DELETE FROM slot_request");
        jdbc.update("DELETE FROM slot_snapshot");
        jdbc.update("DELETE FROM slot_history");
        jdbc.update("DELETE FROM slot_publication");
        jdbc.update("INSERT INTO slot_publication(id, published_at) VALUES (1, NULL)");
        jdbc.update("INSERT INTO slot_history(airline, flight_no, slot_time, executed, planned)"
                + " VALUES ('CA','CA1234','08:30',9,10)");
    }

    private Map<String, Object> body(String requestId, String airline, String flightNo,
                                     String slotTime, List<Integer> weeks) {
        return Map.of("requestId", requestId, "airline", airline, "flightNo", flightNo,
                "slotTime", slotTime, "weeks", weeks);
    }

    @Test
    void applyReturns201WithRetainedAllocation() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/slots/requests",
                body("REQ-1", "CA", "CA1234", "08:30", List.of(1, 2)), JsonNode.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        JsonNode json = response.getBody();
        assertEquals("REQ-1", json.get("requestId").asText());
        assertEquals("ALLOCATED", json.get("status").asText());
        assertEquals(2, json.get("allocations").size());
        assertEquals("RETAINED", json.get("allocations").get(0).get("type").asText());
    }

    @Test
    void replaySameKeySameParamsReturnsFirstResult() {
        rest.postForEntity("/api/slots/requests",
                body("REQ-2", "CA", "CA1234", "08:30", List.of(1)), JsonNode.class);
        ResponseEntity<JsonNode> replay = rest.postForEntity("/api/slots/requests",
                body("REQ-2", "CA", "CA1234", "08:30", List.of(1)), JsonNode.class);
        assertEquals(HttpStatus.CREATED, replay.getStatusCode());
        assertEquals("ALLOCATED", replay.getBody().get("status").asText());
        assertEquals(1, replay.getBody().get("allocations").size());
    }

    @Test
    void sameKeyDifferentParamsReturns409() {
        rest.postForEntity("/api/slots/requests",
                body("REQ-3", "CA", "CA1234", "08:30", List.of(1)), JsonNode.class);
        ResponseEntity<JsonNode> mismatch = rest.postForEntity("/api/slots/requests",
                body("REQ-3", "CA", "CA1234", "09:30", List.of(1)), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, mismatch.getStatusCode());
        assertEquals("IDEMPOTENT_MISMATCH", mismatch.getBody().get("code").asText());
    }

    @Test
    void conflictReturns409WithConflictList() {
        rest.postForEntity("/api/slots/requests",
                body("REQ-4", "CA", "CA1234", "08:30", List.of(1)), JsonNode.class);
        ResponseEntity<JsonNode> conflict = rest.postForEntity("/api/slots/requests",
                body("REQ-5", "MU", "MU200", "08:30", List.of(1)), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        JsonNode json = conflict.getBody();
        assertEquals("SLOT_CONFLICT", json.get("code").asText());
        assertEquals(1, json.get("conflicts").size());
        assertEquals("CA", json.get("conflicts").get(0).get("holder").asText());
        assertEquals(1, json.get("conflicts").get(0).get("week").asInt());
    }

    @Test
    void invalidSlotTimeReturns400() {
        ResponseEntity<JsonNode> badFormat = rest.postForEntity("/api/slots/requests",
                body("REQ-6", "CA", "CA1234", "8:30", List.of(1)), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, badFormat.getStatusCode());
        assertEquals("VALIDATION_FAILED", badFormat.getBody().get("code").asText());

        ResponseEntity<JsonNode> badGranularity = rest.postForEntity("/api/slots/requests",
                body("REQ-7", "CA", "CA1234", "08:32", List.of(1)), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, badGranularity.getStatusCode());
        assertEquals("VALIDATION_FAILED", badGranularity.getBody().get("code").asText());
    }

    @Test
    void emptyWeeksReturns400() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/slots/requests",
                body("REQ-8", "CA", "CA1234", "08:30", List.of()), JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void returnReleasesSlotAndIsIdempotent() {
        rest.postForEntity("/api/slots/requests",
                body("REQ-9", "CA", "CA1234", "08:30", List.of(1)), JsonNode.class);
        ResponseEntity<JsonNode> returned = rest.postForEntity(
                "/api/slots/requests/REQ-9/return", null, JsonNode.class);
        assertEquals(HttpStatus.OK, returned.getStatusCode());
        assertEquals(1, returned.getBody().get("released").size());

        // 退回后他人可立即申请
        ResponseEntity<JsonNode> other = rest.postForEntity("/api/slots/requests",
                body("REQ-10", "MU", "MU200", "08:30", List.of(1)), JsonNode.class);
        assertEquals(HttpStatus.CREATED, other.getStatusCode());

        // 重复退回幂等
        ResponseEntity<JsonNode> again = rest.postForEntity(
                "/api/slots/requests/REQ-9/return", null, JsonNode.class);
        assertEquals(HttpStatus.OK, again.getStatusCode());
        assertEquals(0, again.getBody().get("released").size());
    }

    @Test
    void returnUnknownRequestReturns404() {
        ResponseEntity<JsonNode> response = rest.postForEntity(
                "/api/slots/requests/REQ-NOPE/return", null, JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("NOT_FOUND", response.getBody().get("code").asText());
    }

    @Test
    void publishFreezesPeriodAndRepeatPublishIsIdempotent() {
        rest.postForEntity("/api/slots/requests",
                body("REQ-11", "CA", "CA1234", "08:30", List.of(1)), JsonNode.class);

        ResponseEntity<JsonNode> first = rest.postForEntity("/api/slots/publications",
                null, JsonNode.class);
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertFalse(first.getBody().get("alreadyPublished").asBoolean());
        assertEquals(1, first.getBody().get("publishedCount").asInt());

        ResponseEntity<JsonNode> second = rest.postForEntity("/api/slots/publications",
                null, JsonNode.class);
        assertEquals(HttpStatus.OK, second.getStatusCode());
        assertTrue(second.getBody().get("alreadyPublished").asBoolean());
        assertEquals(first.getBody().get("snapshot"), second.getBody().get("snapshot"));

        // 发布后再申请一律拒绝
        ResponseEntity<JsonNode> frozen = rest.postForEntity("/api/slots/requests",
                body("REQ-12", "MU", "MU200", "10:00", List.of(1)), JsonNode.class);
        assertEquals(HttpStatus.CONFLICT, frozen.getStatusCode());
        assertEquals("PERIOD_FROZEN", frozen.getBody().get("code").asText());

        // 迟到退回不改变已发布快照
        rest.postForEntity("/api/slots/requests/REQ-11/return", null, JsonNode.class);
        ResponseEntity<JsonNode> third = rest.postForEntity("/api/slots/publications",
                null, JsonNode.class);
        assertEquals(1, third.getBody().get("publishedCount").asInt());
        assertEquals(first.getBody().get("snapshot"), third.getBody().get("snapshot"));
    }

    @Test
    void getRequestReturnsCurrentStatus() {
        rest.postForEntity("/api/slots/requests",
                body("REQ-13", "CA", "CA1234", "08:30", List.of(1)), JsonNode.class);
        ResponseEntity<JsonNode> found = rest.getForEntity(
                "/api/slots/requests/REQ-13", JsonNode.class);
        assertEquals(HttpStatus.OK, found.getStatusCode());
        assertEquals("ALLOCATED", found.getBody().get("status").asText());

        ResponseEntity<JsonNode> missing = rest.getForEntity(
                "/api/slots/requests/REQ-NOPE", JsonNode.class);
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }
}
