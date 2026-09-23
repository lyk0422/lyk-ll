package com.example.starter.specimen;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 保管交接 API 测试：持有人校验、时间不倒退、版本校验与交接幂等。
 */
class CustodyApiTest extends ApiTestSupport {

    private String intakeSpecimen() throws Exception {
        postJson("/api/specimens", Map.of("specimenId", "SP-1",
                "volumeUl", new BigDecimal("100"), "custodian", "lab-a"));
        return "SP-1";
    }

    private Map<String, Object> transferBody(String requestId, String entityType, String entityId,
                                             String from, String to, long version, String eventAt) {
        return Map.of("requestId", requestId, "entityType", entityType, "entityId", entityId,
                "fromCustodian", from, "toCustodian", to,
                "expectedVersion", version, "eventAt", eventAt);
    }

    @Test
    void transferRecordsEventAndUpdatesHolder() throws Exception {
        intakeSpecimen();
        MvcResult result = postJson("/api/custody/transfers",
                transferBody("req-tr-1", "SPECIMEN", "SP-1", "lab-a", "lab-b", 0, "2026-09-23T10:00:00Z"));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = body(result);
        assertEquals("lab-b", body.get("custodian").asText());
        assertEquals(1, body.get("version").asLong());
        assertFalse(body.get("replayed").asBoolean());

        JsonNode events = body(getJson("/api/custody/events?entityType=SPECIMEN&entityId=SP-1"));
        assertEquals(1, events.size());
        assertEquals("lab-a", events.get(0).get("fromCustodian").asText());
        assertEquals("lab-b", events.get(0).get("toCustodian").asText());
        assertEquals("req-tr-1", events.get(0).get("requestId").asText());
    }

    @Test
    void transferRejectsWrongCurrentHolder() throws Exception {
        intakeSpecimen();
        MvcResult result = postJson("/api/custody/transfers",
                transferBody("req-tr-1", "SPECIMEN", "SP-1", "lab-x", "lab-b", 0, "2026-09-23T10:00:00Z"));
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("NOT_CURRENT_HOLDER", body(result).get("code").asText());
        assertEquals(0, count("custody_event"));
    }

    @Test
    void transferRejectsStaleVersion() throws Exception {
        intakeSpecimen();
        MvcResult result = postJson("/api/custody/transfers",
                transferBody("req-tr-1", "SPECIMEN", "SP-1", "lab-a", "lab-b", 3, "2026-09-23T10:00:00Z"));
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("VERSION_CONFLICT", body(result).get("code").asText());
        assertEquals(0, count("custody_event"));
    }

    @Test
    void transferRejectsBackwardEventTime() throws Exception {
        intakeSpecimen();
        assertEquals(200, postJson("/api/custody/transfers",
                transferBody("req-tr-1", "SPECIMEN", "SP-1", "lab-a", "lab-b", 0,
                        "2026-09-23T10:00:00Z")).getResponse().getStatus());
        MvcResult backward = postJson("/api/custody/transfers",
                transferBody("req-tr-2", "SPECIMEN", "SP-1", "lab-b", "lab-c", 1,
                        "2026-09-23T09:00:00Z"));
        assertEquals(409, backward.getResponse().getStatus());
        assertEquals("EVENT_TIME_REGRESSION", body(backward).get("code").asText());
        assertEquals(1, count("custody_event"));
    }

    @Test
    void duplicateTransferDoesNotAddEvent() throws Exception {
        intakeSpecimen();
        Map<String, Object> body = transferBody("req-tr-1", "SPECIMEN", "SP-1",
                "lab-a", "lab-b", 0, "2026-09-23T10:00:00Z");
        assertEquals(200, postJson("/api/custody/transfers", body).getResponse().getStatus());
        MvcResult replay = postJson("/api/custody/transfers", body);
        assertEquals(200, replay.getResponse().getStatus());
        assertTrue(body(replay).get("replayed").asBoolean());
        assertEquals("lab-b", body(replay).get("custodian").asText());
        assertEquals(1, count("custody_event"));
    }

    @Test
    void transferSameKeyDifferentParamsReturns409() throws Exception {
        intakeSpecimen();
        assertEquals(200, postJson("/api/custody/transfers",
                transferBody("req-tr-1", "SPECIMEN", "SP-1", "lab-a", "lab-b", 0,
                        "2026-09-23T10:00:00Z")).getResponse().getStatus());
        MvcResult different = postJson("/api/custody/transfers",
                transferBody("req-tr-1", "SPECIMEN", "SP-1", "lab-a", "lab-c", 0,
                        "2026-09-23T10:00:00Z"));
        assertEquals(409, different.getResponse().getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", body(different).get("code").asText());
        assertEquals(1, count("custody_event"));
    }

    @Test
    void transferSupportsAliquotAndAssayEntities() throws Exception {
        intakeSpecimen();
        String aliquotId = body(postJson("/api/specimens/SP-1/aliquots",
                Map.of("requestId", "req-al-1", "expectedVersion", 0,
                        "volumesUl", java.util.List.of(new BigDecimal("10")))))
                .get("aliquotIds").get(0).asText();
        String assayId = body(postJson("/api/assay-requests", Map.of(
                "requestId", "req-as-1", "aliquotId", aliquotId, "expectedVersion", 0,
                "reservedVolumeUl", new BigDecimal("5"), "project", "PCR", "custodian", "lab-a")))
                .get("assayId").asText();

        MvcResult aliquotTransfer = postJson("/api/custody/transfers",
                transferBody("req-tr-1", "ALIQUOT", aliquotId, "lab-a", "lab-b", 1,
                        "2026-09-23T10:00:00Z"));
        assertEquals(200, aliquotTransfer.getResponse().getStatus());

        MvcResult assayTransfer = postJson("/api/custody/transfers",
                transferBody("req-tr-2", "ASSAY", assayId, "lab-a", "lab-b", 0,
                        "2026-09-23T10:05:00Z"));
        assertEquals(200, assayTransfer.getResponse().getStatus());

        assertEquals(1, body(getJson("/api/custody/events?entityType=ALIQUOT&entityId=" + aliquotId)).size());
        assertEquals(1, body(getJson("/api/custody/events?entityType=ASSAY&entityId=" + assayId)).size());
    }

    @Test
    void transferRejectsUnknownEntity() throws Exception {
        MvcResult result = postJson("/api/custody/transfers",
                transferBody("req-tr-1", "SPECIMEN", "SP-404", "lab-a", "lab-b", 0,
                        "2026-09-23T10:00:00Z"));
        assertEquals(404, result.getResponse().getStatus());
    }
}
