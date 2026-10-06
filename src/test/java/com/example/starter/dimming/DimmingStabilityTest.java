package com.example.starter.dimming;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则1：单次调整后须稳定10分钟方可再调，恰满10分钟允许。
 */
class DimmingStabilityTest extends BaseIntegrationTest {

    @Test
    void firstAdjustmentSucceeds() throws Exception {
        var result = dim("TUN-A-01", "stb-1", 4, "MANUAL");
        assertEquals(200, result.getResponse().getStatus());
        assertEquals(3, body(result).get("fromLevel").asInt());
        assertEquals(4, body(result).get("toLevel").asInt());
        assertEquals(4, state("TUN-A-01").get("currentLevel").asInt());
    }

    @Test
    void readjustWithinTenMinutesRejected() throws Exception {
        dim("TUN-A-01", "stb-2a", 4, "MANUAL");
        advanceMinutes(9);
        var rejected = dim("TUN-A-01", "stb-2b", 2, "MANUAL");
        assertEquals(409, rejected.getResponse().getStatus());
        assertEquals("STABILITY_NOT_ELAPSED", body(rejected).get("code").asText());
        assertEquals(4, state("TUN-A-01").get("currentLevel").asInt());
        assertEquals(1, recordCount("TUN-A-01"));
    }

    @Test
    void readjustExactlyAtTenMinutesAllowed() throws Exception {
        dim("TUN-A-01", "stb-3a", 4, "MANUAL");
        advanceMinutes(10);
        var allowed = dim("TUN-A-01", "stb-3b", 2, "MANUAL");
        assertEquals(200, allowed.getResponse().getStatus());
        assertEquals(2, state("TUN-A-01").get("currentLevel").asInt());
        assertEquals(2, recordCount("TUN-A-01"));
    }

    @Test
    void stabilityIsPerCircuit() throws Exception {
        dim("TUN-A-01", "stb-4a", 4, "MANUAL");
        var other = dim("TUN-A-02", "stb-4b", 5, "MANUAL");
        assertEquals(200, other.getResponse().getStatus());
        assertEquals(5, state("TUN-A-02").get("currentLevel").asInt());
    }

    @Test
    void unknownCircuitReturns404() throws Exception {
        var result = dim("NO-SUCH", "stb-5", 4, "MANUAL");
        assertEquals(404, result.getResponse().getStatus());
        assertEquals("CIRCUIT_NOT_FOUND", body(result).get("code").asText());
    }

    @Test
    void invalidLevelRejected() throws Exception {
        var result = mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/circuits/TUN-A-01/commands")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"requestId\":\"stb-6\",\"targetLevel\":9,\"source\":\"MANUAL\"}"))
                .andReturn();
        assertEquals(400, result.getResponse().getStatus());
        assertTrue(body(result).get("code").asText().equals("VALIDATION_FAILED"));
    }
}
