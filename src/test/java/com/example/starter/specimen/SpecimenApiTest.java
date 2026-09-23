package com.example.starter.specimen;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 样本入库与分装 API 测试：精度、守恒、整批回滚、版本与幂等。
 */
class SpecimenApiTest extends ApiTestSupport {

    private Map<String, Object> intakeBody(String specimenId, String volume) {
        return Map.of("specimenId", specimenId, "volumeUl", new BigDecimal(volume), "custodian", "lab-a");
    }

    private Map<String, Object> aliquotBody(String requestId, long version, String... volumes) {
        List<BigDecimal> list = java.util.Arrays.stream(volumes).map(BigDecimal::new).toList();
        return Map.of("requestId", requestId, "expectedVersion", version, "volumesUl", list);
    }

    @Test
    void intakeCreatesSpecimenAndLedgerIsConserved() throws Exception {
        MvcResult created = postJson("/api/specimens", intakeBody("SP-1", "100.000"));
        assertEquals(201, created.getResponse().getStatus());
        JsonNode body = body(created);
        assertEquals(0, new BigDecimal("100.000").compareTo(decimal(body, "totalVolumeUl")));
        assertEquals(0, new BigDecimal("100.000").compareTo(decimal(body, "remainingVolumeUl")));
        assertEquals(0, body.get("version").asLong());

        MvcResult ledger = getJson("/api/specimens/SP-1/ledger");
        assertEquals(200, ledger.getResponse().getStatus());
        JsonNode ledgerBody = body(ledger);
        assertTrue(ledgerBody.get("conserved").asBoolean());
        assertEquals(0, ledgerBody.get("aliquotCount").asLong());
    }

    @Test
    void intakeRejectsDuplicateSpecimenId() throws Exception {
        assertEquals(201, postJson("/api/specimens", intakeBody("SP-1", "10")).getResponse().getStatus());
        MvcResult dup = postJson("/api/specimens", intakeBody("SP-1", "20"));
        assertEquals(409, dup.getResponse().getStatus());
        assertEquals("SPECIMEN_EXISTS", body(dup).get("code").asText());
    }

    @Test
    void intakeRejectsVolumeWithMoreThanThreeDecimals() throws Exception {
        MvcResult result = postJson("/api/specimens", intakeBody("SP-1", "1.0001"));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals("VOLUME_PRECISION", body(result).get("code").asText());
        assertEquals(0, count("specimen"));
    }

    @Test
    void intakeRejectsNonPositiveVolume() throws Exception {
        assertEquals(400, postJson("/api/specimens", intakeBody("SP-1", "0")).getResponse().getStatus());
        assertEquals(400, postJson("/api/specimens", intakeBody("SP-2", "-5")).getResponse().getStatus());
        assertEquals(0, count("specimen"));
    }

    @Test
    void aliquotConservesVolumeExactlyWithThreeDecimals() throws Exception {
        postJson("/api/specimens", intakeBody("SP-1", "100.000"));
        MvcResult result = postJson("/api/specimens/SP-1/aliquots",
                aliquotBody("req-al-1", 0, "30.001", "20.999", "0.001"));
        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = body(result);
        assertEquals(3, body.get("aliquotIds").size());
        assertEquals(0, new BigDecimal("48.999").compareTo(decimal(body, "remainingVolumeUl")));
        assertEquals(1, body.get("version").asLong());

        JsonNode ledger = body(getJson("/api/specimens/SP-1/ledger"));
        assertTrue(ledger.get("conserved").asBoolean());
        assertEquals(0, new BigDecimal("51.001").compareTo(decimal(ledger, "availableVolumeUl")));
        assertEquals(0, new BigDecimal("48.999").compareTo(decimal(ledger, "remainingVolumeUl")));
        assertEquals(3, ledger.get("aliquotCount").asLong());
    }

    @Test
    void aliquotRollsBackWholeBatchWhenAnyVolumeInvalid() throws Exception {
        postJson("/api/specimens", intakeBody("SP-1", "50"));
        MvcResult result = postJson("/api/specimens/SP-1/aliquots",
                aliquotBody("req-al-1", 0, "10.000", "1.0001"));
        assertEquals(400, result.getResponse().getStatus());
        assertEquals(0, count("aliquot"));
        JsonNode ledger = body(getJson("/api/specimens/SP-1/ledger"));
        assertEquals(0, new BigDecimal("50.000").compareTo(decimal(ledger, "remainingVolumeUl")));
        assertTrue(ledger.get("conserved").asBoolean());
    }

    @Test
    void aliquotRejectsOverAllocationAndKeepsRemaining() throws Exception {
        postJson("/api/specimens", intakeBody("SP-1", "100"));
        MvcResult result = postJson("/api/specimens/SP-1/aliquots",
                aliquotBody("req-al-1", 0, "60", "50"));
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("VOLUME_OVERDRAW", body(result).get("code").asText());
        assertEquals(0, count("aliquot"));
        JsonNode ledger = body(getJson("/api/specimens/SP-1/ledger"));
        assertEquals(0, new BigDecimal("100.000").compareTo(decimal(ledger, "remainingVolumeUl")));
    }

    @Test
    void aliquotRejectsStaleVersion() throws Exception {
        postJson("/api/specimens", intakeBody("SP-1", "100"));
        MvcResult result = postJson("/api/specimens/SP-1/aliquots",
                aliquotBody("req-al-1", 5, "10"));
        assertEquals(409, result.getResponse().getStatus());
        assertEquals("VERSION_CONFLICT", body(result).get("code").asText());
        assertEquals(0, count("aliquot"));
    }

    @Test
    void aliquotRejectsUnknownSpecimen() throws Exception {
        MvcResult result = postJson("/api/specimens/SP-404/aliquots",
                aliquotBody("req-al-1", 0, "10"));
        assertEquals(404, result.getResponse().getStatus());
    }

    @Test
    void aliquotSameKeySameParamsReplaysFirstResult() throws Exception {
        postJson("/api/specimens", intakeBody("SP-1", "100"));
        MvcResult first = postJson("/api/specimens/SP-1/aliquots",
                aliquotBody("req-al-1", 0, "10", "20"));
        assertEquals(200, first.getResponse().getStatus());
        MvcResult replay = postJson("/api/specimens/SP-1/aliquots",
                aliquotBody("req-al-1", 0, "10", "20"));
        assertEquals(200, replay.getResponse().getStatus());
        assertEquals(body(first).get("aliquotIds"), body(replay).get("aliquotIds"));
        assertEquals(2, count("aliquot"));
        JsonNode ledger = body(getJson("/api/specimens/SP-1/ledger"));
        assertEquals(0, new BigDecimal("70.000").compareTo(decimal(ledger, "remainingVolumeUl")));
    }

    @Test
    void aliquotSameKeyDifferentParamsReturns409() throws Exception {
        postJson("/api/specimens", intakeBody("SP-1", "100"));
        assertEquals(200, postJson("/api/specimens/SP-1/aliquots",
                aliquotBody("req-al-1", 0, "10")).getResponse().getStatus());
        MvcResult different = postJson("/api/specimens/SP-1/aliquots",
                aliquotBody("req-al-1", 0, "20"));
        assertEquals(409, different.getResponse().getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", body(different).get("code").asText());
        // 同键但版本不同同样视为异参
        MvcResult differentVersion = postJson("/api/specimens/SP-1/aliquots",
                aliquotBody("req-al-1", 1, "10"));
        assertEquals(409, differentVersion.getResponse().getStatus());
        assertEquals("IDEMPOTENCY_CONFLICT", body(differentVersion).get("code").asText());
        assertEquals(1, count("aliquot"));
    }

    @Test
    void aliquotIdsAreUniqueAcrossBatches() throws Exception {
        postJson("/api/specimens", intakeBody("SP-1", "100"));
        MvcResult first = postJson("/api/specimens/SP-1/aliquots", aliquotBody("req-1", 0, "10"));
        MvcResult second = postJson("/api/specimens/SP-1/aliquots", aliquotBody("req-2", 1, "10"));
        assertNotEquals(body(first).get("aliquotIds").get(0).asText(),
                body(second).get("aliquotIds").get(0).asText());
    }
}
