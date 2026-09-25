package com.example.starter;

import com.example.starter.support.ApiTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 出港申报：配额恰达合法、超出整笔拒绝、进行中航次唯一、requestId 幂等。
 */
class DepartureQuotaTest extends ApiTestSupport {

    @Test
    @DisplayName("申报量恰使累计达配额：合法受理")
    void declarationExactlyReachingQuotaAccepted() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        JsonNode response = post("/api/v1/departures",
                departureBody("dep-exact", 1, "2026-03-01T08:00:00Z", "A1", "COD", "1000"), 200);
        assertEquals("DEPARTED", response.get("status").asText());
        assertEquals(1, fixtures.voyageCount(1));
    }

    @Test
    @DisplayName("申报量超出配额：整笔拒绝，不生成航次")
    void declarationExceedingQuotaRejected() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        JsonNode response = post("/api/v1/departures",
                departureBody("dep-over", 1, "2026-03-01T08:00:00Z", "A1", "COD", "1000.001"), 422);
        assertEquals("QUOTA_EXCEEDED", response.get("code").asText());
        assertEquals(0, fixtures.voyageCount(1));
    }

    @Test
    @DisplayName("累计口径含已核销量：核销 600 后申报 400 恰达合法，400.001 拒绝")
    void cumulativeQuotaIncludesSettledUsage() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        fixtures.setQuota(2, "COD", 2026, "400");
        long first = departLogArrive("cum-1", 1, "COD", "600", "2026-03-02T10:00:00Z");
        post("/api/v1/voyages/" + first + "/settlement", requestIdBody("cum-1-set"), 200);
        assertEquals(0, fixtures.usedQuota(1, "COD", 2026).compareTo(new java.math.BigDecimal("600")));

        post("/api/v1/departures",
                departureBody("cum-2-dep", 1, "2026-04-01T08:00:00Z", "A1", "COD", "400"), 200);
        JsonNode rejected = post("/api/v1/departures",
                departureBody("cum-3-dep", 2, "2026-04-01T08:00:00Z", "A1", "COD", "400.001"), 422);
        assertEquals("QUOTA_EXCEEDED", rejected.get("code").asText());
    }

    @Test
    @DisplayName("同一渔船存在进行中航次时，出港申报不予受理")
    void inProgressVoyageBlocksNewDeparture() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        post("/api/v1/departures",
                departureBody("dep-first", 1, "2026-03-01T08:00:00Z", "A1", "COD", "100"), 200);
        JsonNode response = post("/api/v1/departures",
                departureBody("dep-second", 1, "2026-03-02T08:00:00Z", "A1", "COD", "100"), 409);
        assertEquals("CONFLICT", response.get("code").asText());
        assertEquals(1, fixtures.voyageCount(1));
    }

    @Test
    @DisplayName("同 requestId 同参数重放返回首次结果；同键异参返回 409")
    void idempotentReplayAndConflict() throws Exception {
        fixtures.setQuota(1, "COD", 2026, "1000");
        JsonNode first = post("/api/v1/departures",
                departureBody("dep-idem", 1, "2026-03-01T08:00:00Z", "A1", "COD", "100"), 200);
        JsonNode replay = post("/api/v1/departures",
                departureBody("dep-idem", 1, "2026-03-01T08:00:00Z", "A1", "COD", "100"), 200);
        assertEquals(first.get("voyageId").asLong(), replay.get("voyageId").asLong());
        assertEquals(1, fixtures.voyageCount(1));

        JsonNode conflict = post("/api/v1/departures",
                departureBody("dep-idem", 1, "2026-03-01T08:00:00Z", "A1", "COD", "200"), 409);
        assertEquals("IDEMPOTENCY_CONFLICT", conflict.get("code").asText());
        assertEquals(1, fixtures.voyageCount(1));
        assertNotEquals(0, first.get("voyageId").asLong());
    }
}
